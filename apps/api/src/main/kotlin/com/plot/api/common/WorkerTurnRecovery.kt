package com.plot.api.common

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
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
	private val onRetryQueryFailure: (Throwable) -> Unit = {},
) {
	private val log = LoggerFactory.getLogger(WorkerTurnRecovery::class.java)
	private val wakeup = WorkerWakeup(retryExecutor, clock, dispatch)

	fun dispatch(turn: () -> Unit) {
		try {
			taskExecutor.execute {
				var turnSucceeded = false
				try {
					turn()
					turnSucceeded = true
				} catch (ex: RuntimeException) {
					log.warn("Worker turn execution failed; scheduling failure recovery: {}", ex.message)
					scheduleFailureRecovery()
				} finally {
					try {
						armEarliestRetry()
					} catch (ex: RuntimeException) {
						log.warn("Worker turn earliestRetryAt query failed; scheduling failure recovery: {}", ex.message)
						onRetryQueryFailure(ex)
						if (turnSucceeded) {
							scheduleFailureRecovery()
						}
					}
				}
			}
		} catch (_: TaskRejectedException) {
			// A worker turn is already running or queued; it will drain remaining work.
		}
	}

	private fun armEarliestRetry() {
		val retryAt = earliestRetryAt() ?: return
		if (!retryAt.isAfter(clock.instant())) {
			dispatch()
		} else {
			wakeup.scheduleAt(retryAt)
		}
	}

	private fun scheduleFailureRecovery() {
		if (retryExecutor == null) return
		val delayMillis = failureRecoveryDelay.toMillis().coerceAtLeast(1)
		try {
			retryExecutor.schedule({ dispatch() }, delayMillis, TimeUnit.MILLISECONDS)
		} catch (_: RejectedExecutionException) {
			// Executor is shutting down; ignore without crash
		}
	}
}

