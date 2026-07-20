package com.plot.api.certification

import com.plot.api.generation.DurableGenerationCheckpoint
import com.plot.api.generation.GenerationCheckpointObserver
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class CertificationCheckpointGateMode { PAUSE, FAIL }

class CertificationCheckpointReachedException : IllegalStateException("CERTIFICATION_CHECKPOINT_REACHED")
class CertificationCheckpointPauseExpiredException : IllegalStateException("CERTIFICATION_CHECKPOINT_PAUSE_EXPIRED")

/** A single-use, bounded gate that is constructible only with an authorized certification marker. */
class CertificationCheckpointGate(
	@Suppress("unused") private val authorization: AuthorizedCertification,
	private val checkpointName: String,
	private val mode: CertificationCheckpointGateMode,
	private val maxPause: Duration = Duration.ofMinutes(2),
) : GenerationCheckpointObserver {
	private val triggered = AtomicBoolean(false)
	private val reached = CountDownLatch(1)
	private val release = CountDownLatch(1)

	init {
		require(checkpointName.isNotBlank())
		require(!maxPause.isNegative && !maxPause.isZero && maxPause <= Duration.ofMinutes(10))
	}

	override fun afterDurableCheckpoint(checkpoint: DurableGenerationCheckpoint) {
		if (checkpoint.artifactType != checkpointName || !triggered.compareAndSet(false, true)) return
		reached.countDown()
		if (mode == CertificationCheckpointGateMode.FAIL) throw CertificationCheckpointReachedException()
		if (!release.await(maxPause.toMillis(), TimeUnit.MILLISECONDS)) {
			throw CertificationCheckpointPauseExpiredException()
		}
	}

	fun awaitReached(timeout: Duration): Boolean = reached.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

	fun release() = release.countDown()
}
