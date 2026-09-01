package com.plot.api.artifact.workflow

import com.plot.api.common.WorkerWakeup
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException

/**
 * Runs artifact workflow checkpoints on demand and re-arms itself for the
 * earliest invocation retry that is persisted but not yet due.
 */
class ArtifactWorkflowRunDispatcher(
	private val taskExecutor: TaskExecutor,
	private val enabled: Boolean = true,
	retryExecutor: ScheduledExecutorService? = null,
	clock: Clock = Clock.systemUTC(),
	private val earliestRetryAt: () -> Instant? = { null },
	private val drainBatch: () -> Boolean,
) {
	private val wakeup = WorkerWakeup(retryExecutor, clock, ::dispatch)

	fun dispatch() {
		if (!enabled) return
		try {
			taskExecutor.execute {
				while (drainBatch()) {
					// Continue in bounded batches until no runnable checkpoint remains.
				}
				wakeup.scheduleAt(earliestRetryAt())
			}
		} catch (_: TaskRejectedException) {
			// A running or queued drain consumes every currently runnable checkpoint.
		}
	}
}
