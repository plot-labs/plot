package com.plot.api.routine

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Lazy
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.stereotype.Component

@Component
open class RoutineRunDispatcher(
	@Qualifier("routineTaskExecutor")
	private val taskExecutor: TaskExecutor,
	@Lazy private val worker: RoutineWorker,
	private val agentProperties: RoutineAgentProperties,
	@Qualifier("routineRetryExecutor")
	private val retryExecutor: ScheduledExecutorService,
	private val clock: Clock = Clock.systemUTC(),
) {
	private val draining = ThreadLocal.withInitial { false }

	open fun dispatch() {
		if (!agentProperties.workersEnabled || draining.get()) return
		try {
			taskExecutor.execute {
				if (draining.get()) return@execute
				draining.set(true)
				try {
					worker.recover()
					if (worker.drain() > 0) {
						dispatch()
					}
				} finally {
					draining.set(false)
				}
			}
		} catch (_: TaskRejectedException) {
			// A worker turn is already running or queued; it will drain remaining work.
		}
	}

	open fun scheduleDelayed(at: Instant) {
		if (!agentProperties.workersEnabled) return
		val delay = Duration.between(clock.instant(), at).coerceAtLeast(Duration.ZERO)
		if (delay.isZero) {
			if (!draining.get()) dispatch()
			return
		}
		retryExecutor.schedule({ dispatch() }, delay.toMillis(), TimeUnit.MILLISECONDS)
	}
}
