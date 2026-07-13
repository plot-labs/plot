package com.plot.api.generation

import java.time.Clock
import java.time.Duration
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

class GenerationRunRecovery(
	private val persistence: GenerationPersistence,
	private val worker: GenerationRunWorker,
	private val dispatcher: GenerationRunDispatcher,
	private val clock: Clock = Clock.systemUTC(),
	private val claimTimeout: Duration = Duration.ofMinutes(2),
) {
	@EventListener(ApplicationReadyEvent::class)
	fun recover(): RecoveryResult {
		val releasedClaims = persistence.recoverStaleClaims(clock.instant().minus(claimTimeout))
		val processedCheckpoints = worker.drain()
		if (processedCheckpoints > 0) dispatcher.dispatch()
		return RecoveryResult(releasedClaims, processedCheckpoints)
	}
}

data class RecoveryResult(val releasedClaims: Int, val processedCheckpoints: Int)
