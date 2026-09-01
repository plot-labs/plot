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
open class AgentRunDispatcher(
	@Qualifier("agentRunTaskExecutor")
	private val taskExecutor: TaskExecutor,
	@Lazy private val worker: AgentRunWorker,
	private val properties: RoutineAgentProperties,
	@Qualifier("agentRunRetryExecutor")
	private val retryExecutor: ScheduledExecutorService,
	private val clock: Clock = Clock.systemUTC(),
) {
	open fun dispatch() {
		if (!properties.workersEnabled) return
		try {
			taskExecutor.execute {
				// One turn drains every runnable agent run; the single worker slot serialises turns.
				do {
					worker.recover()
				} while (worker.drain() > 0)
			}
		} catch (_: TaskRejectedException) {
			// A worker turn is already running or queued; it will drain remaining work.
		}
	}

	open fun scheduleDelayed(at: Instant) {
		if (!properties.workersEnabled) return
		val delay = Duration.between(clock.instant(), at).coerceAtLeast(Duration.ZERO)
		if (delay.isZero) {
			dispatch()
			return
		}
		retryExecutor.schedule({ dispatch() }, delay.toMillis(), TimeUnit.MILLISECONDS)
	}
}
