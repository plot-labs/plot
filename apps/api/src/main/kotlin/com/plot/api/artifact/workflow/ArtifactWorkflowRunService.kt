package com.plot.api.artifact.workflow

import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.config.PlotAiProperties
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.SourceProvider
import com.plot.api.routine.AgentBudgetSnapshot
import com.plot.api.routine.AgentRunInputRecord
import com.plot.api.routine.AgentRunRecord
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper

@Service
class ArtifactWorkflowRunService(
	private val workflowService: ArtifactWorkflowService,
	private val persistence: ArtifactWorkflowPersistence,
	private val dispatcher: ArtifactWorkflowRunDispatcher,
	private val uuidGenerator: UuidGenerator,
	private val properties: PlotAiProperties,
	private val objectMapper: ObjectMapper,
) {
	fun createForAgent(
		principal: WorkspacePrincipal,
		agentRun: AgentRunRecord,
		inputs: List<AgentRunInputRecord>,
		idempotencyKey: String,
	): ArtifactWorkflowState {
		require(agentRun.workspaceId == principal.workspaceId) { "Agent run belongs to another workspace" }
		require(agentRun.createdByUserId == principal.userId) { "Agent run belongs to another principal" }
		require(idempotencyKey.isNotBlank()) { "Idempotency key is required" }
		require(inputs.isNotEmpty()) { "At least one Agent input is required" }
		require(inputs.all { it.workspaceId == principal.workspaceId && it.agentRunId == agentRun.id }) {
			"Agent input belongs to another run"
		}
		require(inputs.map { it.id }.distinct().size == inputs.size) { "Agent input IDs must be unique" }
		require(inputs.map { it.writingBlockId }.distinct().size == inputs.size) {
			"Selected Agent inputs must reference distinct source items"
		}
		val ordered = inputs.sortedWith(compareBy(AgentRunInputRecord::orderIndex, AgentRunInputRecord::id))
		val evidenceLimit = agentEvidenceLimit(agentRun)
		val evidenceCharacters = ordered.sumOf { it.snapshotTitle.orEmpty().length + it.snapshotBody.length }
		require(evidenceCharacters <= evidenceLimit) {
			"Selected evidence exceeds the frozen Agent evidence limit"
		}
		val normalizedKey = idempotencyKey.trim()
		val requestFingerprint = fingerprint(agentRun, ordered)
		persistence.findIdempotentRun(
			principal.workspaceId,
			principal.userId,
			normalizedKey,
			requestFingerprint,
		)?.let {
			if (it.status !in ArtifactWorkflowRunStatus.terminalOrPaused) dispatchAfterCommit()
			return it
		}

		val runId = uuidGenerator.next()
		val artifactRunId = uuidGenerator.next()
		val evidence = ordered.mapIndexed { index, input ->
			EvidenceSnapshot(
				id = uuidGenerator.next(),
				artifactWorkflowRunId = runId,
				writingBlockId = input.writingBlockId,
				orderIndex = index,
				sourceProvider = SourceProvider.valueOf(input.sourceProvider),
				sourceKind = input.sourceKind,
				sourceLabel = input.sourceLabel,
				snapshotTitle = input.snapshotTitle,
				snapshotBody = input.snapshotBody,
				snapshotExcerpt = input.snapshotExcerpt,
				originalUrl = input.originalUrl,
				sourceCreatedAt = input.sourceCreatedAt,
				sourceUpdatedAt = input.sourceUpdatedAt,
				contentHash = input.contentHash,
				capturedAt = input.capturedAt,
				sourceScopeId = input.sourceScopeId,
				agentRunId = agentRun.id,
				agentRunInputId = input.id,
			)
		}
		val initialState = workflowService.start(runId, evidence, agentRun.instructionSnapshot).copy(
			agentRunId = agentRun.id,
		)
		val state = persistence.createRun(
			ArtifactWorkflowRunReservation(
				workspaceId = principal.workspaceId,
				createdByUserId = principal.userId,
				sourceScopeId = null,
				idempotencyKey = normalizedKey,
				requestFingerprint = requestFingerprint,
				state = initialState,
				provider = properties.provider.uppercase(),
				modelName = properties.model?.trim().takeUnless { it.isNullOrBlank() } ?: "not-configured",
					budgetJson = artifactWorkflowBudgetJson(),
				workSessionId = requireNotNull(agentRun.workSessionId) {
					"Agent run must have a Chat before creating an artifact workflow"
				},
				agentRunId = agentRun.id,
				artifactRunId = artifactRunId,
			),
		)
		dispatchAfterCommit()
		return state
	}

	private fun dispatchAfterCommit() {
		if (
			TransactionSynchronizationManager.isActualTransactionActive() &&
			TransactionSynchronizationManager.isSynchronizationActive()
		) {
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() {
					dispatcher.dispatch()
				}
			})
		} else {
			dispatcher.dispatch()
		}
	}

	private fun fingerprint(agentRun: AgentRunRecord, inputs: List<AgentRunInputRecord>): String {
		val canonical = buildString {
			append(agentRun.id).append('\n')
			append(agentRun.instructionSnapshot.trim()).append('\n')
			append(agentRun.promptVersion).append('\n')
			inputs.forEach { input ->
				append(input.orderIndex).append('|')
				append(input.sourceScopeId).append('|')
				append(input.writingBlockId).append('|')
				append(input.sourceUpdatedAt?.toEpochMilli()?.toString().orEmpty()).append('|')
				append(input.contentHash).append('\n')
			}
		}
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
	}

	private fun artifactWorkflowBudgetJson(): String = objectMapper.writeValueAsString(
		mapOf(
			"maxModelCalls" to properties.maxModelCalls,
			"maxTotalTokens" to properties.maxTotalTokens,
			"maxRunDurationMillis" to properties.maxRunDuration.toMillis(),
		),
	)

	private fun agentEvidenceLimit(agentRun: AgentRunRecord): Int = try {
		objectMapper.readValue(agentRun.budgetSnapshotJson, AgentBudgetSnapshot::class.java)
			.maxEvidenceCharacters
			.also { require(it > 0) }
	} catch (_: RuntimeException) {
		throw IllegalArgumentException("Agent budget snapshot is invalid")
	}
}

class ArtifactWorkflowSourceAccessException : IllegalArgumentException("One or more Writing Blocks are unavailable in the source scope")
