package com.plot.api.github

import com.plot.api.common.WorkerWakeup
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException

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
	clock: Clock = Clock.systemUTC(),
) {
	private val wakeup = WorkerWakeup(retryExecutor, clock, ::dispatch)

	fun dispatch() {
		try {
			taskExecutor.execute {
				do {
					recover()
				} while (drain() > 0)
				onQueueEmpty()
				wakeup.scheduleAt(earliestRetryAt())
			}
		} catch (_: TaskRejectedException) {
			// A worker turn is already running or queued; it will drain remaining work.
		}
	}
}
