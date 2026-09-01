package com.plot.api.routine

import com.plot.api.common.WorkerTurnRecovery
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Lazy
import org.springframework.core.task.TaskExecutor
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
	private val recovery = WorkerTurnRecovery(
		taskExecutor = taskExecutor,
		retryExecutor = retryExecutor,
		clock = clock,
		failureRecoveryDelay = properties.claimTimeout,
		earliestRetryAt = worker::earliestNextAttemptAt,
		dispatch = ::dispatch,
	)

	open fun dispatch() {
		if (!properties.workersEnabled) return
		recovery.dispatch {
			do {
				worker.recover()
			} while (worker.drain() > 0)
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
