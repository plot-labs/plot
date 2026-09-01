package com.plot.api.common

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.core.task.SyncTaskExecutor

class WorkerTurnRecoveryTest {
	private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
	private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

	@Test
	fun `failed turn schedules claim-timeout recovery`() {
		val retryExecutor = Executors.newSingleThreadScheduledExecutor()
		try {
			val recovered = CountDownLatch(1)
			val recovery = WorkerTurnRecovery(
				taskExecutor = SyncTaskExecutor(),
				retryExecutor = retryExecutor,
				clock = clock,
				failureRecoveryDelay = Duration.ofMillis(50),
				earliestRetryAt = { null },
				dispatch = { recovered.countDown() },
			)

			recovery.dispatch { error("worker turn failed") }

			assertTrue(recovered.await(2, TimeUnit.SECONDS))
		} finally {
			retryExecutor.shutdownNow()
		}
	}

	@Test
	fun `successful turn arms the earliest persisted retry`() {
		val retryExecutor = Executors.newSingleThreadScheduledExecutor()
		try {
			val turns = AtomicInteger()
			val redispatched = CountDownLatch(2)
			lateinit var recovery: WorkerTurnRecovery
			recovery = WorkerTurnRecovery(
				taskExecutor = SyncTaskExecutor(),
				retryExecutor = retryExecutor,
				clock = clock,
				failureRecoveryDelay = Duration.ofMinutes(5),
				earliestRetryAt = {
					redispatched.countDown()
					now.plusMillis(50).takeIf { turns.incrementAndGet() < 2 }
				},
				dispatch = { recovery.dispatch { } },
			)

			recovery.dispatch { }

			assertTrue(redispatched.await(2, TimeUnit.SECONDS))
		} finally {
			retryExecutor.shutdownNow()
		}
	}
}
