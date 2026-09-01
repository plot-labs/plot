package com.plot.api.common

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException

/**
 * Runs one serialized worker turn, arms the earliest persisted retry, and schedules
 * a claim-timeout recovery wakeup when the turn fails before finishing.
 */
internal class WorkerTurnRecovery(
	private val taskExecutor: TaskExecutor,
	private val retryExecutor: ScheduledExecutorService?,
	private val clock: Clock,
	private val failureRecoveryDelay: Duration,
	private val earliestRetryAt: () -> Instant?,
	private val dispatch: () -> Unit,
) {
	private val wakeup = WorkerWakeup(retryExecutor, clock, dispatch)

	fun dispatch(turn: () -> Unit) {
		try {
			taskExecutor.execute {
				try {
					turn()
				} catch (_: RuntimeException) {
					scheduleFailureRecovery()
				} finally {
					wakeup.scheduleAt(earliestRetryAt())
				}
			}
		} catch (_: TaskRejectedException) {
			// A worker turn is already running or queued; it will drain remaining work.
		}
	}

	private fun scheduleFailureRecovery() {
		if (retryExecutor == null) return
		val delayMillis = failureRecoveryDelay.toMillis().coerceAtLeast(1)
		retryExecutor.schedule({ dispatch() }, delayMillis, TimeUnit.MILLISECONDS)
	}
}
