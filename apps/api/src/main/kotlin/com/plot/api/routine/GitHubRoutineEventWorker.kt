package com.plot.api.routine

import com.plot.api.common.ApiException
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.generation.GenerationRunService
import com.plot.api.writingblock.WritingBlockRepository
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class GitHubRoutineEventWorker(
	private val eventPersistence: GitHubRoutineEventPersistence,
	private val routinePersistence: RoutinePersistence,
	private val writingBlockRepository: WritingBlockRepository,
	private val generationRunService: GenerationRunService,
	private val evidenceBudget: RoutineEvidenceBudget,
	private val transactionTemplate: TransactionTemplate,
	private val clock: Clock = Clock.systemUTC(),
	private val workerId: String = "routine-github-${UUID.randomUUID()}",
	private val claimTimeout: Duration = Duration.ofMinutes(2),
) {
	fun drain(): Int {
		val item = eventPersistence.claimNext(workerId, clock.instant(), claimTimeout) ?: return 0
		try {
			transactionTemplate.executeWithoutResult {
				routinePersistence.lockWorkspaceActivity(item.workspaceId)
				processLocked(item)
			}
		} catch (_: GitHubRoutineEventClaimLostException) {
			// A stale replacement owns the durable event now.
		} catch (failure: RuntimeException) {
			if (!isPermanent(failure)) throw failure
			try {
				eventPersistence.fail(item, safeErrorCode(failure), clock.instant())
			} catch (_: GitHubRoutineEventClaimLostException) {
				// A stale replacement owns the durable event now.
			}
		}
		return 1
	}

	private fun processLocked(item: GitHubRoutineEventRun) {
		val routine = routinePersistence.find(item.workspaceId, item.routineId)
			?: throw GitHubRoutineEventPermanentException("ROUTINE_NOT_FOUND")
		if (!routine.enabled) throw GitHubRoutineEventPermanentException("ROUTINE_DISABLED")
		if (routine.cadence !in EVENT_CADENCES) {
			throw GitHubRoutineEventPermanentException("ROUTINE_TRIGGER_CHANGED")
		}
		if (!routinePersistence.isSourceActive(item.workspaceId, routine.sourceScopeId)) {
			throw GitHubRoutineEventPermanentException("SOURCE_NOT_READY")
		}
		val selected = writingBlockRepository.findSelectedReadable(
			item.workspaceId,
			routine.sourceScopeId,
			item.writingBlockIds,
		).associateBy { it.id }
		if (selected.size != item.writingBlockIds.size) {
			throw GitHubRoutineEventPermanentException("SOURCE_NOT_READY")
		}
		if (item.evidence.any { selected.getValue(it.writingBlockId).activitySequence != it.activitySequence }) {
			throw GitHubRoutineEventPermanentException("ROUTINE_EVIDENCE_CHANGED")
		}
		val ordered = item.writingBlockIds.map(selected::getValue)
		val characters = ordered.sumOf { evidenceBudget.characters(it.title, it.body) }
		if (ordered.size > evidenceBudget.maxBlocks || characters > evidenceBudget.maxCharacters) {
			throw GitHubRoutineEventPermanentException("ROUTINE_EVIDENCE_TOO_LARGE")
		}
		evidenceBudget.requireWithinBudget(ordered.size, characters)
		val generation = generationRunService.createForPrincipal(
			principal = WorkspacePrincipal(routine.workspaceId, routine.createdByUserId),
			sourceScopeId = routine.sourceScopeId,
			writingBlockIds = item.writingBlockIds,
			instruction = routine.instruction,
			idempotencyKey = "routine-github:${item.id}",
		)
		eventPersistence.succeed(item, generation.runId, clock.instant())
	}

	private fun isPermanent(failure: RuntimeException): Boolean =
		failure is GitHubRoutineEventPermanentException ||
			failure is ApiException ||
			failure is IllegalArgumentException

	private fun safeErrorCode(failure: RuntimeException): String = when (failure) {
		is GitHubRoutineEventPermanentException -> failure.safeErrorCode
		is ApiException -> failure.error.takeIf { it.matches(SAFE_ERROR_CODE) } ?: "ROUTINE_EVENT_FAILED"
		else -> "ROUTINE_EVENT_FAILED"
	}

	private companion object {
		val EVENT_CADENCES = setOf(
			RoutineCadence.ON_GITHUB_CHANGE,
			RoutineCadence.ON_GITHUB_RELEASE,
			RoutineCadence.ON_GIT_TAG,
		)
		val SAFE_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,99}")
	}
}
