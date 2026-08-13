package com.plot.api.artifact.workflow

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface ArtifactWorkflowRunLeaseFactory {
	fun open(claim: ClaimedArtifactWorkflowRun): ArtifactWorkflowRunLeaseHandle
}

class ArtifactWorkflowRunLeaseHandle(
	val lease: ArtifactWorkflowRunLease,
	private val closeAction: () -> Unit,
) : AutoCloseable {
	override fun close() = closeAction()
}

class ScheduledArtifactWorkflowRunLeaseFactory(
	private val executor: ScheduledExecutorService,
	private val heartbeatInterval: Duration,
	private val clock: Clock,
	private val renewClaim: (ClaimedArtifactWorkflowRun, Instant) -> Boolean,
) : ArtifactWorkflowRunLeaseFactory {
	init {
		require(!heartbeatInterval.isNegative && !heartbeatInterval.isZero) {
			"artifact workflow heartbeat interval must be positive"
		}
	}

	override fun open(claim: ClaimedArtifactWorkflowRun): ArtifactWorkflowRunLeaseHandle {
		val lease = ArtifactWorkflowRunLease(claim, renewClaim, clock)
		val heartbeat = executor.scheduleAtFixedRate(
			lease::renew,
			heartbeatInterval.toMillis(),
			heartbeatInterval.toMillis(),
			TimeUnit.MILLISECONDS,
		)
		return ArtifactWorkflowRunLeaseHandle(lease) { heartbeat.cancel(false) }
	}
}

class ArtifactWorkflowRunLease(
	private val claim: ClaimedArtifactWorkflowRun,
	private val renewClaim: (ClaimedArtifactWorkflowRun, Instant) -> Boolean,
	private val clock: Clock,
) {
	private val lost = AtomicBoolean(false)
	private val transitionLock = Any()

	fun renew() {
		if (lost.get()) return
		synchronized(transitionLock) {
			try {
				if (!renewClaim(claim, clock.instant())) {
					lost.set(true)
				}
			} catch (_: RuntimeException) {
				lost.set(true)
			}
		}
	}

	fun checkpoint() {
		if (lost.get()) throw ArtifactWorkflowRunLeaseLostException()
	}

	fun <T> commit(action: () -> T): T = synchronized(transitionLock) {
		checkpoint()
		action()
	}
}

class ArtifactWorkflowRunLeaseLostException :
	IllegalStateException("ArtifactWorkflow run claim ownership was lost")
