package com.plot.api.routine

import com.plot.api.writingblock.WritingBlock
import com.plot.api.writingblock.WritingBlockRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Claims canonical RoutineExecutions and performs only the durable admission
 * step.  The AgentRun worker/model loop is intentionally a later boundary.
 */
@Component
class RoutineWorker(
	private val persistence: RoutinePersistence,
	private val agentPersistence: RoutineAgentPersistence,
	private val writingBlockRepository: WritingBlockRepository,
	private val evidenceBudget: RoutineEvidenceBudget,
	private val transactionTemplate: TransactionTemplate,
	private val clock: Clock? = null,
	private val workerId: String = "routine-${UUID.randomUUID()}",
	private val claimTimeout: Duration = Duration.ofMinutes(2),
) {
	@Scheduled(fixedDelayString = "\${plot.routines.poll-delay:PT5S}")
	fun poll() {
		drain()
	}

	fun drain(): Boolean {
		val now = currentInstant()
		val canonicalExecution = agentPersistence.claimNext(workerId, now, now.minus(claimTimeout))
		if (canonicalExecution != null) {
			process(canonicalExecution, claimedRoutine = null)
			return true
		}

		val routine = persistence.claimNext(workerId, now, now.minus(claimTimeout)) ?: return false
		val scheduledExecution = ensureScheduledExecution(routine)
		val claimedExecution = agentPersistence.claimById(
			workerId,
			routine.workspaceId,
			scheduledExecution.id,
			now,
			now.minus(claimTimeout),
		)
		if (claimedExecution != null) process(claimedExecution, routine)
		return true
	}

	fun runNow(workspaceId: UUID, id: UUID, executionId: UUID? = null) {
		val now = currentInstant()
		val routine = persistence.find(workspaceId, id) ?: return
		val execution = executionId?.let { agentPersistence.findExecution(workspaceId, it) }
			?: routine.activeExecutionId?.let { agentPersistence.findExecution(workspaceId, it) }
			?: ensureManualExecution(routine)
		val claimedExecution = agentPersistence.claimById(
			workerId,
			workspaceId,
			execution.id,
			now,
			now.minus(claimTimeout),
		)
		if (claimedExecution != null) process(claimedExecution, claimedRoutine = null)
	}

	private fun process(execution: RoutineExecutionRecord, claimedRoutine: RoutineRecord?) {
		try {
			transactionTemplate.executeWithoutResult {
				persistence.lockWorkspaceActivity(execution.workspaceId)
				processLocked(execution, claimedRoutine)
			}
		} catch (_: RoutineClaimLostException) {
			// A stale replacement owns this execution now.
		} catch (failure: RuntimeException) {
			if (!isPermanent(failure)) throw failure
			val now = currentInstant()
			try {
				if (agentPersistence.findExecution(execution.workspaceId, execution.id)?.status == RoutineExecutionStatus.PROBING) {
					agentPersistence.failExecution(execution.workspaceId, execution.id, safeErrorCode(failure), now, workerId)
				}
				finishProjection(
					execution = execution,
					claimedRoutine = claimedRoutine,
					now = now,
					status = "FAILED",
					nextRunAt = nextRunAtFor(execution, claimedRoutine ?: persistence.find(execution.workspaceId, execution.routineId), now),
					errorCode = safeErrorCode(failure),
				)
			} catch (_: RoutineClaimLostException) {
				// A stale replacement owns this execution now.
			}
		}
	}

	private fun processLocked(execution: RoutineExecutionRecord, claimedRoutine: RoutineRecord?) {
		if (execution.status != RoutineExecutionStatus.PROBING) return
		val routine = persistence.find(execution.workspaceId, execution.routineId)
			?: throw RoutineExecutionStateException("Routine was not found")
		if (!routine.enabled && execution.triggerKind != RoutineExecutionTriggerKind.MANUAL) {
			completeFailure(execution, claimedRoutine, routine, "ROUTINE_DISABLED")
			return
		}
		if (!persistence.isSourceActive(execution.workspaceId, routine.sourceScopeId)) {
			completeFailure(execution, claimedRoutine, routine, "SOURCE_NOT_READY")
			return
		}
		if (routine.activityCursorSequence != execution.activityCursorBefore) {
			completeFailure(execution, claimedRoutine, routine, "ROUTINE_CURSOR_STALE")
			return
		}

		val candidates = candidates(execution, routine)
		if (candidates.isEmpty()) {
			agentPersistence.markNoActivity(execution.workspaceId, execution.id, currentInstant(), workerId)
			finishProjection(
				execution,
				claimedRoutine,
				currentInstant(),
				status = "NO_ACTIVITY",
				nextRunAt = nextRunAtFor(execution, routine, currentInstant()),
			)
			return
		}

		val batch = evidenceBudget.select(candidates)
		val oversized = batch.blocks.isEmpty() && batch.oversizedBlockCount == 1
		val selected = if (oversized) listOf(candidates.first()) else batch.blocks
		if (selected.isEmpty() || batch.consumedThrough == null) {
			throw RoutineExecutionStateException("Routine evidence preflight did not produce a cursor")
		}
		if (!oversized) {
			evidenceBudget.requireWithinBudget(
				selected.size,
				selected.sumOf { evidenceBudget.characters(it.title, it.body) },
			)
		}

		val now = currentInstant()
		agentPersistence.dispatch(
			workspaceId = execution.workspaceId,
			executionId = execution.id,
			request = AgentRunDispatchRequest(
				title = "${routine.name} · routine check",
				instructionSnapshot = routine.instruction,
				promptVersion = PROMPT_VERSION,
				toolPolicyVersion = TOOL_POLICY_VERSION,
				budgetSnapshotJson = if (oversized) OVERSIZED_BUDGET_JSON else BUDGET_JSON,
				sourceScopes = sourceScopes(execution, routine, now),
				inputs = selected.mapIndexed { index, block -> seedInput(execution, block, index, now) },
				activityCursorAfter = batch.consumedThrough.sequence,
			),
			now = now,
			workerId = workerId,
		)
		finishProjection(
			execution,
			claimedRoutine,
			now,
			status = "QUEUED",
			nextRunAt = nextRunAtFor(execution, routine, now),
		)
	}

	private fun candidates(execution: RoutineExecutionRecord, routine: RoutineRecord): List<WritingBlock> {
		if (execution.triggerKind != RoutineExecutionTriggerKind.GITHUB) {
			return writingBlockRepository.findUnconsumedActiveAfterActivityCursor(
				routine.workspaceId,
				routine.id,
				routine.sourceScopeId,
				routine.activityCursorSequence,
				PageRequest.of(0, evidenceBudget.maxBlocks + 1),
			)
		}

		val evidence = agentPersistence.listEvidence(execution.workspaceId, execution.id)
		if (evidence.isEmpty()) return emptyList()
		val ids = evidence.map { it.writingBlockId }
		val selected = writingBlockRepository.findSelectedReadable(
			execution.workspaceId,
			routine.sourceScopeId,
			ids,
		).associateBy { it.id }
		if (selected.size != ids.size) throw RoutineExecutionStateException("Routine source evidence is unavailable")
		if (evidence.any { selected.getValue(it.writingBlockId).activitySequence != it.activitySequence }) {
			throw RoutineExecutionStateException("Routine source evidence changed")
		}
		return evidence
			.sortedBy { it.orderIndex }
			.map { selected.getValue(it.writingBlockId) }
			.filter { it.activitySequence > (routine.activityCursorSequence ?: 0L) }
	}

	private fun sourceScopes(
		execution: RoutineExecutionRecord,
		routine: RoutineRecord,
		now: Instant,
	): List<AgentRunSourceRequest> {
		val context = agentPersistence.listContextSources(execution.workspaceId, routine.id)
		return buildList {
			add(
				AgentRunSourceRequest(
					sourceScopeId = routine.sourceScopeId,
					role = AgentRunSourceRole.TRIGGER,
					capturedStatusChangedAt = now,
				),
			)
			context.forEach {
				add(
					AgentRunSourceRequest(
						sourceScopeId = it.sourceScopeId,
						role = AgentRunSourceRole.CONTEXT,
						capturedStatusChangedAt = now,
					),
				)
			}
		}
	}

	private fun seedInput(
		execution: RoutineExecutionRecord,
		block: WritingBlock,
		orderIndex: Int,
		now: Instant,
	): AgentRunInputRequest {
		val title = block.title.orEmpty().take(evidenceBudget.maxCharacters)
		val bodyLimit = (evidenceBudget.maxCharacters - title.length).coerceAtLeast(1)
		val body = (block.body ?: block.title ?: "Activity").take(bodyLimit).ifBlank { "Activity" }
		return AgentRunInputRequest(
			routineId = execution.routineId,
			sourceScopeId = execution.triggerSourceScopeId,
			writingBlockId = block.id,
			inputKind = AgentRunInputKind.SEED,
			orderIndex = orderIndex,
			activitySequence = block.activitySequence,
			snapshotTitle = title.ifBlank { null },
			snapshotBody = body,
			snapshotExcerpt = body.take(240),
			originalUrl = block.canonicalUrl ?: block.url ?: "plot://writing-block/${block.id}",
			sourceCreatedAt = block.sourceCreatedAt,
			sourceUpdatedAt = block.sourceUpdatedAt,
			contentHash = block.contentHash ?: "activity:${block.id}:${block.activitySequence}",
			capturedAt = now,
		)
	}

	private fun ensureScheduledExecution(routine: RoutineRecord): RoutineExecutionRecord {
		val executionId = requireNotNull(routine.activeExecutionId) { "Routine claim has no execution identity" }
		val existing = agentPersistence.findExecution(routine.workspaceId, executionId)
		if (existing != null) return existing
		return agentPersistence.createExecution(
			RoutineExecutionRequest(
				id = executionId,
				workspaceId = routine.workspaceId,
				routineId = routine.id,
				createdByUserId = routine.createdByUserId,
				triggerSourceScopeId = routine.sourceScopeId,
				triggerKind = RoutineExecutionTriggerKind.SCHEDULED,
				triggerKey = "scheduled:${routine.id}:${routine.nextRunAt.toEpochMilli()}",
				requestFingerprint = fingerprint(routine, routine.nextRunAt),
				scheduledFor = routine.nextRunAt,
				activityCursorBefore = routine.activityCursorSequence,
			),
		)
	}

	private fun ensureManualExecution(routine: RoutineRecord): RoutineExecutionRecord = agentPersistence.createExecution(
		RoutineExecutionRequest(
			id = routine.activeExecutionId,
			workspaceId = routine.workspaceId,
			routineId = routine.id,
			createdByUserId = routine.createdByUserId,
			triggerSourceScopeId = routine.sourceScopeId,
			triggerKind = RoutineExecutionTriggerKind.MANUAL,
			triggerKey = "manual:${routine.id}:legacy:${requireNotNull(routine.activeExecutionId)}",
			requestFingerprint = fingerprint(routine, routine.nextRunAt),
			activityCursorBefore = routine.activityCursorSequence,
		),
	)

	private fun completeFailure(
		execution: RoutineExecutionRecord,
		claimedRoutine: RoutineRecord?,
		routine: RoutineRecord,
		errorCode: String,
	) {
		val now = currentInstant()
		agentPersistence.failExecution(execution.workspaceId, execution.id, errorCode, now, workerId)
		finishProjection(execution, claimedRoutine, now, "FAILED", nextRunAtFor(execution, routine, now), errorCode)
	}

	private fun nextRunAtFor(execution: RoutineExecutionRecord, routine: RoutineRecord?, now: Instant): Instant =
		when {
			routine == null -> now
			execution.triggerKind != RoutineExecutionTriggerKind.SCHEDULED -> routine.nextRunAt
			else -> routine.cadence.nextAfter(execution.scheduledFor ?: routine.nextRunAt)
		}

	private fun finishProjection(
		execution: RoutineExecutionRecord,
		claimedRoutine: RoutineRecord?,
		now: Instant,
		status: String,
		nextRunAt: Instant,
		errorCode: String? = null,
	) {
		if (claimedRoutine != null) {
			persistence.finish(
				claim = claimedRoutine,
				now = now,
				nextRunAt = nextRunAt,
				status = status,
				errorCode = errorCode,
			)
			return
		}
		agentPersistence.projectRoutine(
			workspaceId = execution.workspaceId,
			routineId = execution.routineId,
			executionId = execution.id,
			now = now,
			nextRunAt = nextRunAt,
			status = status,
			errorCode = errorCode,
			projectionAt = execution.refreshFrom ?: execution.scheduledFor ?: execution.createdAt,
		)
	}

	private fun fingerprint(routine: RoutineRecord, scheduledFor: Instant): String = buildString {
		append(routine.id).append('|')
		append(routine.sourceScopeId).append('|')
		append(routine.cadence.name).append('|')
		append(routine.instruction).append('|')
		append(PROMPT_VERSION).append('|')
		append(TOOL_POLICY_VERSION).append('|')
		append(scheduledFor.toEpochMilli())
		agentPersistence.listContextSources(routine.workspaceId, routine.id)
			.forEach { append('|').append(it.sourceScopeId) }
	}

	private fun isPermanent(failure: RuntimeException): Boolean =
		generateSequence<Throwable>(failure) { it.cause }
			.any { it is RoutineExecutionStateException || it is IllegalArgumentException }

	private fun safeErrorCode(failure: RuntimeException): String {
		val messages = generateSequence<Throwable>(failure) { it.cause }
			.mapNotNull { it.message }
			.joinToString(" ")
		return when {
			messages.contains("source evidence changed", ignoreCase = true) -> "ROUTINE_EVIDENCE_CHANGED"
			messages.contains("source evidence is unavailable", ignoreCase = true) -> "SOURCE_NOT_READY"
			messages.contains("cursor", ignoreCase = true) -> "ROUTINE_CURSOR_STALE"
			messages.contains("active", ignoreCase = true) -> "SOURCE_NOT_READY"
			else -> "ROUTINE_RUN_FAILED"
		}
	}

	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	private companion object {
		const val PROMPT_VERSION = "routine-agent-v1"
		const val TOOL_POLICY_VERSION = "read-only-v1"
		const val BUDGET_JSON = "{\"kind\":\"routine-agent\",\"bounded\":true}"
		const val OVERSIZED_BUDGET_JSON = "{\"kind\":\"routine-agent\",\"bounded\":true,\"truncated\":true}"
	}
}
