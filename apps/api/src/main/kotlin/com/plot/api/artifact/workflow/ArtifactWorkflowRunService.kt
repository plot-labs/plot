package com.plot.api.artifact.workflow

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.config.PlotAiProperties
import com.plot.api.dev.DevContext
import com.plot.api.artifact.workflow.dto.ArtifactWorkflowRunTimingResponse
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.SourceProvider
import com.plot.api.routine.AgentBudgetSnapshot
import com.plot.api.routine.AgentRunInputRecord
import com.plot.api.routine.AgentRunRecord
import com.plot.api.source.SourceManagedAccessGuard
import com.plot.api.writingblock.WritingBlockRepository
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper

@Service
class ArtifactWorkflowRunService(
	private val devContext: DevContext,
	private val writingBlockRepository: WritingBlockRepository,
	private val sourceManagedAccessGuard: SourceManagedAccessGuard,
	private val evidenceSnapshotService: EvidenceSnapshotService,
	private val workflowService: ArtifactWorkflowService,
	private val persistence: ArtifactWorkflowPersistence,
	private val dispatcher: ArtifactWorkflowRunDispatcher,
	private val uuidGenerator: UuidGenerator,
	private val properties: PlotAiProperties,
	private val objectMapper: ObjectMapper,
) {
	fun create(
		sourceScopeId: UUID,
		writingBlockIds: List<UUID>,
		instruction: String?,
		idempotencyKey: String,
		workSessionId: UUID? = null,
	): ArtifactWorkflowState =
		createInternal(
			principal = WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId),
			sourceScopeId = sourceScopeId,
			writingBlockIds = writingBlockIds,
			instruction = instruction,
			idempotencyKey = idempotencyKey,
			requireRequestSourceAccess = true,
			workSessionId = workSessionId,
		)

	fun createForPrincipal(
		principal: WorkspacePrincipal,
		sourceScopeId: UUID,
		writingBlockIds: List<UUID>,
		instruction: String?,
		idempotencyKey: String,
		workSessionId: UUID? = null,
	): ArtifactWorkflowState =
		createInternal(
			principal = principal,
			sourceScopeId = sourceScopeId,
			writingBlockIds = writingBlockIds,
			instruction = instruction,
			idempotencyKey = idempotencyKey,
			requireRequestSourceAccess = false,
			workSessionId = workSessionId,
		)

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
				budgetJson = generationBudgetJson(),
				workSessionId = requireNotNull(agentRun.workSessionId) {
					"Agent run must have a Chat before creating a generation"
				},
				agentRunId = agentRun.id,
				artifactRunId = artifactRunId,
			),
		)
		dispatchAfterCommit()
		return state
	}

	private fun createInternal(
		principal: WorkspacePrincipal,
		sourceScopeId: UUID,
		writingBlockIds: List<UUID>,
		instruction: String?,
		idempotencyKey: String,
		requireRequestSourceAccess: Boolean,
		workSessionId: UUID?,
	): ArtifactWorkflowState {
		require(idempotencyKey.isNotBlank()) { "Idempotency key is required" }
		require(writingBlockIds.isNotEmpty()) { "At least one Writing Block is required" }
		require(writingBlockIds.distinct().size == writingBlockIds.size) { "Writing Block IDs must be unique" }
		val normalizedKey = idempotencyKey.trim()
		val requestFingerprint = fingerprint(sourceScopeId, writingBlockIds, instruction, workSessionId)
		persistence.findIdempotentRun(
			principal.workspaceId, principal.userId, normalizedKey, requestFingerprint,
		)?.let {
			if (it.status !in ArtifactWorkflowRunStatus.terminalOrPaused) dispatchAfterCommit()
			return it
		}
		val selected = writingBlockRepository.findSelectedReadable(
			principal.workspaceId,
			sourceScopeId,
			writingBlockIds,
		).associateBy { it.id }
		if (selected.size != writingBlockIds.size) throw ArtifactWorkflowSourceAccessException()
		if (requireRequestSourceAccess && selected.values.any { it.sourceNamespaceId != null }) {
			sourceManagedAccessGuard.requireReadable()
		}
		val evidenceCharacters = selected.values.sumOf { it.title.orEmpty().length + it.body.orEmpty().length }
		require(evidenceCharacters <= properties.maxEvidenceCharacters) {
			"Selected evidence exceeds the ${properties.maxEvidenceCharacters} character limit"
		}
		val runId = uuidGenerator.next()
		val evidence = writingBlockIds.mapIndexed { index, id ->
			evidenceSnapshotService.snapshot(runId, index, selected.getValue(id), sourceScopeId)
		}
		val initialState = workflowService.start(runId, evidence, instruction).copy(workSessionId = workSessionId)
		val state = persistence.createRun(ArtifactWorkflowRunReservation(
			workspaceId = principal.workspaceId,
			createdByUserId = principal.userId,
			sourceScopeId = sourceScopeId,
			idempotencyKey = normalizedKey,
			requestFingerprint = requestFingerprint,
			state = initialState,
			provider = properties.provider.uppercase(),
			modelName = properties.model?.trim().takeUnless { it.isNullOrBlank() } ?: "not-configured",
			budgetJson = generationBudgetJson(),
			workSessionId = workSessionId,
			))
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

	fun get(runId: UUID): ArtifactWorkflowState = try {
		persistence.loadState(devContext.devWorkspaceId, runId)
	} catch (_: ArtifactWorkflowRunNotFoundException) {
		throw ApiException(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND", "ArtifactWorkflow run not found")
	}

	fun getTiming(runId: UUID): ArtifactWorkflowRunTimingResponse = try {
		persistence.loadTiming(devContext.devWorkspaceId, runId)
	} catch (_: ArtifactWorkflowRunNotFoundException) {
		throw ApiException(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND", "ArtifactWorkflow run not found")
	}

	private fun fingerprint(sourceScopeId: UUID, ids: List<UUID>, instruction: String?, workSessionId: UUID?): String {
		val canonical = buildString {
			append(sourceScopeId).append('\n')
			ids.forEach { append(it).append('\n') }
			append(instruction?.trim().orEmpty())
			if (workSessionId != null) append('\n').append(workSessionId)
		}
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
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

	private fun generationBudgetJson(): String = objectMapper.writeValueAsString(
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
