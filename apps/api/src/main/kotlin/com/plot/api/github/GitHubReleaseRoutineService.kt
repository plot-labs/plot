package com.plot.api.github

import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.agent.AgentBudgetSnapshot
import com.plot.api.routine.RoutineAgentAdmissionPersistence
import com.plot.api.routine.RoutineAgentDispatchRequest
import com.plot.api.agent.AgentRunDispatcher
import com.plot.api.agent.AgentRunInputKind
import com.plot.api.agent.AgentRunInputRequest
import com.plot.api.agent.AgentRunRecord
import com.plot.api.agent.AgentRunSourceRequest
import com.plot.api.agent.AgentRunSourceRole
import com.plot.api.routine.RoutineAgentPersistence
import com.plot.api.agent.AgentProperties
import com.plot.api.routine.RoutineEvidenceBudget
import com.plot.api.routine.RoutineExecutionRecord
import com.plot.api.routine.RoutineExecutionRequest
import com.plot.api.routine.RoutineExecutionTriggerKind
import com.plot.api.routine.RoutinePersistence
import com.plot.api.routine.RoutineRecord
import com.plot.api.writingblock.WritingBlockRepository
import java.time.Instant
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper

/** Release executions use a pinned range, never the Routine's repository activity cursor. */
@Service
class GitHubReleaseRoutineService(
	private val routines: RoutinePersistence,
	private val executions: RoutineAgentPersistence,
	private val admission: RoutineAgentAdmissionPersistence,
	private val blocks: WritingBlockRepository,
	private val budget: RoutineEvidenceBudget,
	private val properties: AgentProperties,
	private val workspaceAccess: WorkspaceAccessService,
	private val objectMapper: ObjectMapper,
	@Lazy private val dispatcher: AgentRunDispatcher,
) {
	@Transactional
	fun ensureExecution(request: GitHubReleaseDraftRequest): RoutineExecutionRecord {
		val routine = routine(request)
		val key = "github-release:${request.id}:attempt:${request.runAttempt}"
		return executions.findByTriggerKey(request.workspaceId, routine.id, key)
			?: executions.createExecution(
				RoutineExecutionRequest(
					workspaceId = routine.workspaceId,
					routineId = routine.id,
					createdByUserId = routine.createdByUserId,
					triggerSourceScopeId = routine.sourceScopeId,
					triggerKind = RoutineExecutionTriggerKind.GITHUB,
					triggerKey = key,
					requestFingerprint = key,
					triggerDeliveryId = request.initialDeliveryId,
					releaseRequestId = request.id,
				),
			)
	}

	fun prepare(request: GitHubReleaseDraftRequest) {
		ensureExecution(request)
		requireEnabled(request)
		workspaceAccess.requireWritable(request.workspaceId)
	}

	@Transactional
	fun admit(request: GitHubReleaseDraftRequest, evidence: GitHubReleaseEvidence): AgentRunRecord {
		val routine = requireEnabled(request)
		workspaceAccess.requireWritable(request.workspaceId)
		val execution = ensureExecution(request)
		val now = Instant.now()
		val selected = blocks.findSelectedReadable(request.workspaceId, request.sourceScopeId, evidence.writingBlockIds)
			.associateBy { it.id }
		if (selected.size != evidence.writingBlockIds.size) {
			throw GitHubReleasePermanentException("GITHUB_RELEASE_EVIDENCE_UNAVAILABLE")
		}
		val inputs = evidence.writingBlockIds.mapIndexed { index, id ->
			val block = selected.getValue(id)
			val body = block.body?.takeIf { it.isNotBlank() } ?: block.title.orEmpty()
			if (body.isBlank() || body.length + block.title.orEmpty().length > properties.maxInputCharacters) {
				throw GitHubReleasePermanentException("GITHUB_RELEASE_EVIDENCE_TOO_LARGE")
			}
			AgentRunInputRequest(
				routineId = routine.id,
				sourceScopeId = routine.sourceScopeId,
				writingBlockId = id,
				sourceProvider = "GITHUB",
				sourceKind = block.sourceKind,
				sourceLabel = block.title?.take(240)?.takeIf { it.isNotBlank() } ?: "GitHub ${block.sourceKind}",
				inputKind = AgentRunInputKind.SEED,
				orderIndex = index,
				activitySequence = block.activitySequence,
				snapshotTitle = block.title,
				snapshotBody = body,
				snapshotExcerpt = body.take(240),
				originalUrl = block.canonicalUrl ?: block.url
					?: throw GitHubReleasePermanentException("GITHUB_RELEASE_EVIDENCE_UNAVAILABLE"),
				sourceCreatedAt = block.sourceCreatedAt,
				sourceUpdatedAt = block.sourceUpdatedAt,
				contentHash = block.contentHash
					?: throw GitHubReleasePermanentException("GITHUB_RELEASE_EVIDENCE_UNAVAILABLE"),
				capturedAt = now,
			)
		}
		val characters = inputs.sumOf { it.snapshotTitle.orEmpty().length + it.snapshotBody.length }
		if (inputs.size !in 1..budget.maxBlocks || characters > minOf(budget.maxCharacters, properties.maxEvidenceCharacters)) {
			throw GitHubReleasePermanentException("GITHUB_RELEASE_EVIDENCE_TOO_LARGE")
		}
		executions.addEvidence(request.workspaceId, execution.id, evidence.writingBlockIds, now)
		val run = admission.dispatch(
			request.workspaceId,
			execution.id,
			RoutineAgentDispatchRequest(
				instructionSnapshot = "${routine.instruction}\n\nRelease ${request.tagName}: use only the verified release seed evidence for shipped changes. Context sources are background, not additional release activity.",
				promptVersion = "routine-agent-v1",
				toolPolicyVersion = "read-only-v1",
				budgetSnapshotJson = objectMapper.writeValueAsString(
					AgentBudgetSnapshot(
						properties.maxModelCalls, properties.maxToolCalls, properties.maxRunDuration.toMillis(),
						properties.maxInputCharacters, properties.maxEvidenceCharacters,
					),
				),
				maxAttempts = properties.maxAttempts,
				sourceScopes = listOf(AgentRunSourceRequest(routine.sourceScopeId, AgentRunSourceRole.TRIGGER, capturedStatusChangedAt = now)) +
					executions.listContextSources(request.workspaceId, routine.id).map {
						AgentRunSourceRequest(it.sourceScopeId, AgentRunSourceRole.CONTEXT, capturedStatusChangedAt = now)
					},
				inputs = inputs,
				activityCursorAfter = inputs.maxOf { requireNotNull(it.activitySequence) },
				requestedModel = routine.model,
				requestedReasoningEffort = routine.reasoningEffort,
			),
			now,
		)
		executions.projectRoutine(
			workspaceId = request.workspaceId, routineId = routine.id, executionId = execution.id,
			now = now, nextRunAt = routine.nextRunAt, status = "QUEUED", projectionAt = execution.createdAt,
		)
		if (properties.autoDispatchEnabled) {
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() = dispatcher.dispatch()
			})
		}
		return run
	}

	private fun requireEnabled(request: GitHubReleaseDraftRequest): RoutineRecord = routine(request).also {
		if (!it.enabled) throw GitHubReleasePermanentException("ROUTINE_DISABLED")
		if (!routines.isSourceActive(request.workspaceId, request.sourceScopeId)) {
			throw GitHubReleasePermanentException("SOURCE_ACCESS_LOST")
		}
	}

	private fun routine(request: GitHubReleaseDraftRequest): RoutineRecord =
		request.routineId?.let { routines.find(request.workspaceId, it) }
			?.takeIf { it.sourceScopeId == request.sourceScopeId }
			?: throw GitHubReleasePermanentException("GITHUB_RELEASE_ROUTINE_UNAVAILABLE")
}
