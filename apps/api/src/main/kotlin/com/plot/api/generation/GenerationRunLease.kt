package com.plot.api.generation

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface GenerationRunLeaseFactory {
	fun open(claim: ClaimedGenerationRun): GenerationRunLeaseHandle
}

class GenerationRunLeaseHandle(
	val lease: GenerationRunLease,
	private val closeAction: () -> Unit,
) : AutoCloseable {
	override fun close() = closeAction()
}

class ScheduledGenerationRunLeaseFactory(
	private val executor: ScheduledExecutorService,
	private val heartbeatInterval: Duration,
	private val clock: Clock,
	private val renewClaim: (ClaimedGenerationRun, Instant) -> Boolean,
) : GenerationRunLeaseFactory {
	init {
		require(!heartbeatInterval.isNegative && !heartbeatInterval.isZero) {
			"generation heartbeat interval must be positive"
		}
	}

	override fun open(claim: ClaimedGenerationRun): GenerationRunLeaseHandle {
		val lease = GenerationRunLease(claim, renewClaim, clock)
		val heartbeat = executor.scheduleAtFixedRate(
			lease::renew,
			heartbeatInterval.toMillis(),
			heartbeatInterval.toMillis(),
			TimeUnit.MILLISECONDS,
		)
		return GenerationRunLeaseHandle(lease) { heartbeat.cancel(false) }
	}
}

class GenerationRunLease(
	private val claim: ClaimedGenerationRun,
	private val renewClaim: (ClaimedGenerationRun, Instant) -> Boolean,
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
		if (lost.get()) throw GenerationRunLeaseLostException()
	}

	fun <T> commit(action: () -> T): T = synchronized(transitionLock) {
		checkpoint()
		action()
	}
}

class GenerationRunLeaseLostException :
	IllegalStateException("Generation run claim ownership was lost")
