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

@Component
class RoutineWorker(
	private val persistence: RoutinePersistence,
	private val writingBlockRepository: WritingBlockRepository,
	private val generationRunService: GenerationRunService,
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
		val now = currentInstant()
		val after = routine.lastRunAt ?: Instant.EPOCH
		try {
			val blocks = writingBlockRepository.findActiveSince(
				routine.workspaceId,
				routine.sourceScopeId,
				after,
				PageRequest.of(0, MAX_BLOCKS),
			)
			val nextRunAt = routine.cadence.nextAfter(now)
			if (blocks.isEmpty()) {
				persistence.finish(
					claim = routine,
					now = now,
					nextRunAt = nextRunAt,
					status = "NO_ACTIVITY",
				)
				return
			}
			val generation = generationRunService.createForPrincipal(
				principal = WorkspacePrincipal(routine.workspaceId, routine.createdByUserId),
				sourceScopeId = routine.sourceScopeId,
				writingBlockIds = blocks.map { it.id },
				instruction = routine.instruction,
				idempotencyKey = "routine:${routine.id}:${routine.nextRunAt}",
			)
			persistence.finish(
				claim = routine,
				now = now,
				nextRunAt = nextRunAt,
				status = "QUEUED",
				generationRunId = generation.runId,
			)
		} catch (failure: RuntimeException) {
			persistence.finish(
				claim = routine,
				now = now,
				nextRunAt = routine.cadence.nextAfter(now),
				status = "FAILED",
				errorCode = safeErrorCode(failure),
				advanceCursor = false,
			)
		}
	}

	private fun safeErrorCode(failure: RuntimeException): String = when (failure) {
		is ApiException -> failure.error.takeIf { it.matches(SAFE_ERROR_CODE) } ?: "ROUTINE_RUN_FAILED"
		else -> "ROUTINE_RUN_FAILED"
	}

	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	private companion object {
		const val MAX_BLOCKS = 20
		val SAFE_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,99}")
	}
}
