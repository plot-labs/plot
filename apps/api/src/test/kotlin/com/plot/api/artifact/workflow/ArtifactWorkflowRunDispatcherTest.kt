package com.plot.api.artifact.workflow

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.core.task.SyncTaskExecutor

class ArtifactWorkflowRunDispatcherTest {
	@Test
	fun `dispatch wakes the generation worker`() {
		val drains = AtomicInteger()
		val dispatcher = ArtifactWorkflowRunDispatcher(SyncTaskExecutor()) { drains.incrementAndGet() < 3 }

		dispatcher.dispatch()

		assertEquals(3, drains.get())
	}

	@Test
	fun `disabled dispatcher does not wake the generation worker`() {
		val drains = AtomicInteger()
		val dispatcher = ArtifactWorkflowRunDispatcher(SyncTaskExecutor(), enabled = false) { drains.incrementAndGet() < 3 }

		dispatcher.dispatch()

		assertEquals(0, drains.get())
	}

	@Test
	fun `dispatch redispatches at the persisted retry time`() {
		val retryExecutor = Executors.newSingleThreadScheduledExecutor()
		try {
			val drains = AtomicInteger()
			val retried = CountDownLatch(2)
			val retryAt = Instant.parse("2026-01-01T00:00:00Z").plusMillis(50)
			val dispatcher = ArtifactWorkflowRunDispatcher(
				taskExecutor = SyncTaskExecutor(),
				retryExecutor = retryExecutor,
				clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
				earliestRetryAt = { retryAt.takeIf { drains.get() < 2 } },
			) {
				drains.incrementAndGet()
				retried.countDown()
				false
			}

			dispatcher.dispatch()

			assertTrue(retried.await(2, TimeUnit.SECONDS))
		} finally {
			retryExecutor.shutdownNow()
		}
	}

	@Test
	fun `unexpected drain failure stops the current dispatch turn`() {
		val attempts = AtomicInteger()
		val retried = CountDownLatch(1)
		val dispatcher = ArtifactWorkflowRunDispatcher(SyncTaskExecutor()) {
			if (attempts.incrementAndGet() == 1) error("database unavailable")
			retried.countDown()
			false
		}

		assertFailsWith<IllegalStateException> { dispatcher.dispatch() }

		assertFalse(retried.await(1200, TimeUnit.MILLISECONDS))
		assertEquals(1, attempts.get())
	}
}
