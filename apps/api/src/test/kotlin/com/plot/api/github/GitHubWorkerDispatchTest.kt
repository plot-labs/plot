package com.plot.api.github

import java.time.Clock
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

class GitHubWorkerDispatchTest {
	private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

	@Test
	fun `one turn drains every eligible request`() {
		val drains = AtomicInteger()
		val emptied = AtomicInteger()
		val dispatch = GitHubWorkerDispatch(
			taskExecutor = SyncTaskExecutor(),
			retryExecutor = null,
			recover = { 0 },
			drain = { if (drains.incrementAndGet() < 3) 1 else 0 },
			earliestRetryAt = { null },
			onQueueEmpty = { emptied.incrementAndGet() },
			clock = Clock.fixed(now, ZoneOffset.UTC),
		)

		dispatch.dispatch()

		assertEquals(3, drains.get())
		assertEquals(1, emptied.get())
	}

	@Test
	fun `a persisted future retry wakes the queue again`() {
		val retryExecutor = Executors.newSingleThreadScheduledExecutor()
		try {
			val turns = AtomicInteger()
			val redispatched = CountDownLatch(2)
			val dispatch = GitHubWorkerDispatch(
				taskExecutor = SyncTaskExecutor(),
				retryExecutor = retryExecutor,
				recover = { 0 },
				drain = { 0 },
				earliestRetryAt = {
					redispatched.countDown()
					now.plusMillis(50).takeIf { turns.incrementAndGet() < 2 }
				},
				clock = Clock.fixed(now, ZoneOffset.UTC),
			)

			dispatch.dispatch()

			assertTrue(redispatched.await(2, TimeUnit.SECONDS))
		} finally {
			retryExecutor.shutdownNow()
		}
	}
}
