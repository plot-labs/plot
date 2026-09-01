package com.plot.api.artifact.workflow

import com.plot.api.common.WorkerTurnRecovery
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import org.springframework.core.task.TaskExecutor

/**
 * Runs artifact workflow checkpoints on demand and re-arms itself for the
 * earliest invocation retry that is persisted but not yet due.
 */
class ArtifactWorkflowRunDispatcher(
	private val taskExecutor: TaskExecutor,
	private val enabled: Boolean = true,
	retryExecutor: ScheduledExecutorService? = null,
	clock: Clock = Clock.systemUTC(),
	failureRecoveryDelay: Duration = Duration.ofMinutes(2),
	private val earliestRetryAt: () -> Instant? = { null },
	private val drainBatch: () -> Boolean,
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
		if (!enabled) return
		recovery.dispatch {
			while (drainBatch()) {
				// Continue in bounded batches until no runnable checkpoint remains.
			}
		}
	}
}
