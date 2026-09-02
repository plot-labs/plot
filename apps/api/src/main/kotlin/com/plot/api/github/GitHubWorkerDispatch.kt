package com.plot.api.github

import com.plot.api.common.WorkerTurnRecovery
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import org.springframework.core.task.TaskExecutor

/**
 * Drains one GitHub queue on demand and re-arms itself for the earliest retry
 * that is persisted but not yet due.
 */
internal class GitHubWorkerDispatch(
	private val taskExecutor: TaskExecutor,
	retryExecutor: ScheduledExecutorService?,
	private val recover: () -> Int,
	private val drain: () -> Int,
	private val earliestRetryAt: () -> Instant?,
	private val onQueueEmpty: () -> Unit = {},
	failureRecoveryDelay: Duration = Duration.ofMinutes(2),
	clock: Clock = Clock.systemUTC(),
) {
	private val recovery = WorkerTurnRecovery(
		taskExecutor = taskExecutor,
		retryExecutor = retryExecutor,
		clock = clock,
		failureRecoveryDelay = failureRecoveryDelay,
		earliestRetryAt = earliestRetryAt,
		dispatch = ::dispatch,
	)

	fun dispatch() {
		recovery.dispatch {
			do {
				recover()
			} while (drain() > 0)
			onQueueEmpty()
		}
	}
}
