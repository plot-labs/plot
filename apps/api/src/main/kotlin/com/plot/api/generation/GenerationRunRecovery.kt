package com.plot.api.generation

import java.time.Clock
import java.time.Duration
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled

class GenerationRunRecovery(
	private val persistence: GenerationPersistence,
	private val dispatcher: GenerationRunDispatcher,
	private val clock: Clock = Clock.systemUTC(),
	private val claimTimeout: Duration = Duration.ofMinutes(2),
) {
	@EventListener(ApplicationReadyEvent::class)
	fun recover(): RecoveryResult {
		val releasedClaims = persistence.recoverStaleClaims(clock.instant().minus(claimTimeout))
		dispatcher.dispatch()
		return RecoveryResult(releasedClaims, 0)
	}

	@Scheduled(fixedDelayString = "\${plot.ai.worker-poll-delay:PT5S}")
	fun poll() {
		recover()
	}
}

data class RecoveryResult(val releasedClaims: Int, val processedCheckpoints: Int)
