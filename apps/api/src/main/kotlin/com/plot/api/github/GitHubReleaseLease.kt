package com.plot.api.github

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

interface GitHubReleaseLease {
	val workerId: String
	val transitionVersion: Long
	fun checkpoint()
	fun advanceTransition()
	fun transition(action: (Long) -> Unit) {
		checkpoint()
		action(transitionVersion)
		advanceTransition()
	}
}

class GitHubReleaseLeaseHandle(
	val lease: GitHubReleaseLease,
	private val closeAction: () -> Unit,
) : AutoCloseable {
	override fun close() = closeAction()
}

interface GitHubReleaseLeaseFactory {
	fun open(request: GitHubReleaseDraftRequest, workerId: String): GitHubReleaseLeaseHandle
}

@Component
class DefaultGitHubReleaseLeaseFactory(
	private val persistence: GitHubReleasePersistence,
	@Qualifier("githubReleaseHeartbeatExecutor")
	private val heartbeatExecutor: ScheduledExecutorService,
	private val properties: GitHubProperties,
	private val clock: Clock = Clock.systemUTC(),
) : GitHubReleaseLeaseFactory {
	override fun open(
		request: GitHubReleaseDraftRequest,
		workerId: String,
	): GitHubReleaseLeaseHandle {
		val lease = DefaultGitHubReleaseLease(
			persistence = persistence,
			requestId = request.id,
			initialTransitionVersion = request.transitionVersion,
			workerId = workerId,
			clock = clock,
		)
		val intervalMillis = heartbeatInterval(properties.releaseWorkerLeaseTimeout).toMillis()
		val heartbeat = heartbeatExecutor.scheduleAtFixedRate(
			lease::renew,
			intervalMillis,
			intervalMillis,
			TimeUnit.MILLISECONDS,
		)
		return GitHubReleaseLeaseHandle(lease) { heartbeat.cancel(false) }
	}

	private fun heartbeatInterval(leaseTimeout: Duration): Duration =
		leaseTimeout.dividedBy(3).coerceAtLeast(Duration.ofMillis(10))
}

class DefaultGitHubReleaseLease(
	private val persistence: GitHubReleasePersistence,
	private val requestId: java.util.UUID,
	initialTransitionVersion: Long,
	override val workerId: String,
	private val clock: Clock,
) : GitHubReleaseLease {
	private val version = AtomicLong(initialTransitionVersion)
	private val lost = AtomicBoolean(false)
	private val transitionLock = Any()

	override val transitionVersion: Long
		get() = version.get()

	fun renew() {
		if (lost.get()) return
		synchronized(transitionLock) {
			try {
				if (!persistence.renewClaim(requestId, version.get(), workerId, clock.instant())) {
					lost.set(true)
				}
			} catch (_: RuntimeException) {
				lost.set(true)
			}
		}
	}

	override fun checkpoint() {
		if (lost.get()) throw GitHubReleaseLeaseLostException()
	}

	override fun advanceTransition() {
		version.incrementAndGet()
		checkpoint()
	}

	override fun transition(action: (Long) -> Unit) {
		synchronized(transitionLock) {
			checkpoint()
			action(version.get())
			version.incrementAndGet()
			checkpoint()
		}
	}
}

class GitHubReleaseLeaseLostException : IllegalStateException("GitHub release claim ownership was lost")

private fun Duration.coerceAtLeast(minimum: Duration): Duration =
	if (this < minimum) minimum else this
