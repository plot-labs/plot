package com.plot.api.common

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot timer that re-arms an event-driven worker at the earliest persisted
 * retry instant, so a queue whose only remaining work is scheduled in the future
 * still wakes up without a poll loop. Wakeups are disabled when no executor is
 * configured.
 */
class WorkerWakeup(
	private val executor: ScheduledExecutorService?,
	private val clock: Clock,
	private val dispatch: () -> Unit,
) {
	private val armedFor = AtomicReference<Instant?>()

	fun scheduleAt(at: Instant?) {
		if (at == null || executor == null) return
		while (true) {
			val armed = armedFor.get()
			if (armed != null && !armed.isAfter(at)) return
			if (armedFor.compareAndSet(armed, at)) break
		}
		val delay = Duration.between(clock.instant(), at).toMillis().coerceAtLeast(1)
		executor.schedule(
			{
				if (armedFor.compareAndSet(at, null)) dispatch()
			},
			delay,
			TimeUnit.MILLISECONDS,
		)
	}
}
