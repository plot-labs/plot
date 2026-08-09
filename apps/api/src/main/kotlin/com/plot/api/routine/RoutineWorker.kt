package com.plot.api.routine

import com.plot.api.common.ApiException
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.generation.GenerationRunService
import com.plot.api.writingblock.WritingBlockRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class RoutineWorker(
	private val persistence: RoutinePersistence,
	private val writingBlockRepository: WritingBlockRepository,
	private val generationRunService: GenerationRunService,
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
		val routine = persistence.claimNext(workerId, now, now.minus(claimTimeout)) ?: return false
		process(routine)
		return true
	}

	fun runNow(workspaceId: UUID, id: UUID) {
		val now = currentInstant()
		val routine = persistence.claimById(workerId, workspaceId, id, now, now.minus(claimTimeout))
		if (routine != null) process(routine)
	}

	private fun process(routine: RoutineRecord) {
		try {
			transactionTemplate.executeWithoutResult {
				persistence.lockWorkspaceActivity(routine.workspaceId)
				processLocked(routine)
			}
		} catch (_: RoutineClaimLostException) {
			// A stale replacement owns this execution now.
		} catch (failure: RuntimeException) {
			if (!isPermanent(failure)) throw failure
			val now = currentInstant()
			try {
				persistence.finish(
					claim = routine,
					now = now,
					nextRunAt = routine.cadence.nextAfter(now),
					status = "FAILED",
					errorCode = safeErrorCode(failure),
				)
			} catch (_: RoutineClaimLostException) {
				// A stale replacement owns this execution now.
			}
		}
	}

	private fun processLocked(routine: RoutineRecord) {
		val now = currentInstant()
		val nextRunAt = routine.cadence.nextAfter(now)
		if (!persistence.isSourceActive(routine.workspaceId, routine.sourceScopeId)) {
			persistence.finish(
				claim = routine,
				now = now,
				nextRunAt = nextRunAt,
				status = "FAILED",
				errorCode = "SOURCE_NOT_READY",
			)
			return
		}
		val candidates = writingBlockRepository.findActiveAfterActivityCursor(
			routine.workspaceId,
			routine.sourceScopeId,
			routine.activityCursorSequence,
			PageRequest.of(0, evidenceBudget.maxBlocks + 1),
		)
		if (candidates.isEmpty()) {
			persistence.finish(
				claim = routine,
				now = now,
				nextRunAt = nextRunAt,
				status = "NO_ACTIVITY",
			)
			return
		}
		val batch = evidenceBudget.select(candidates)
		if (batch.blocks.isEmpty()) {
			check(batch.oversizedBlockCount == 1 && batch.consumedThrough != null) {
				"An empty routine evidence batch must identify one oversized block"
			}
			persistence.finish(
				claim = routine,
				now = now,
				nextRunAt = nextRunAt,
				status = "FAILED",
				errorCode = "ROUTINE_EVIDENCE_TOO_LARGE",
				activityCursor = batch.consumedThrough,
			)
			return
		}
		evidenceBudget.requireWithinBudget(
			batch.blocks.size,
			batch.blocks.sumOf { evidenceBudget.characters(it.title, it.body) },
		)
		val generation = generationRunService.createForPrincipal(
			principal = WorkspacePrincipal(routine.workspaceId, routine.createdByUserId),
			sourceScopeId = routine.sourceScopeId,
			writingBlockIds = batch.blocks.map { it.id },
			instruction = routine.instruction,
			idempotencyKey = "routine:${routine.id}:${requireNotNull(routine.activeExecutionId)}",
		)
		persistence.finish(
			claim = routine,
			now = now,
			nextRunAt = nextRunAt,
			status = "QUEUED",
			generationRunId = generation.runId,
			activityCursor = batch.consumedThrough,
		)
	}

	private fun isPermanent(failure: RuntimeException): Boolean =
		failure is ApiException || failure is IllegalArgumentException

	private fun safeErrorCode(failure: RuntimeException): String = when (failure) {
		is ApiException -> failure.error.takeIf { it.matches(SAFE_ERROR_CODE) } ?: "ROUTINE_RUN_FAILED"
		else -> "ROUTINE_RUN_FAILED"
	}

	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	private companion object {
		val SAFE_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,99}")
	}
}
