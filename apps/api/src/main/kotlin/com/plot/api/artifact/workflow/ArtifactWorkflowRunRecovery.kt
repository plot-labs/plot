package com.plot.api.artifact.workflow

import java.time.Clock
import java.time.Duration
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

class ArtifactWorkflowRunRecovery(
	private val persistence: ArtifactWorkflowRecoveryPersistence,
	private val dispatcher: ArtifactWorkflowRunDispatcher,
	private val clock: Clock = Clock.systemUTC(),
	private val claimTimeout: Duration = Duration.ofMinutes(2),
	private val workerEnabled: Boolean = true,
) {
	@EventListener(ApplicationReadyEvent::class)
	fun recover(): RecoveryResult {
		val releasedClaims = persistence.recoverStaleClaims(clock.instant().minus(claimTimeout))
		if (workerEnabled) dispatcher.dispatch()
		return RecoveryResult(releasedClaims, 0)
	}
}

data class RecoveryResult(val releasedClaims: Int, val processedCheckpoints: Int)
