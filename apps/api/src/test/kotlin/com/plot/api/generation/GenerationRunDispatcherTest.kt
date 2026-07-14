package com.plot.api.generation

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.core.task.SyncTaskExecutor

class GenerationRunDispatcherTest {
	@Test
	fun `dispatch wakes the generation worker`() {
		val drains = AtomicInteger()
		val dispatcher = GenerationRunDispatcher(SyncTaskExecutor()) { drains.incrementAndGet() < 3 }

		dispatcher.dispatch()

		assertEquals(3, drains.get())
	}

	@Test
	fun `dispatch re-arms after an unexpected drain failure`() {
		val attempts = AtomicInteger()
		val retried = CountDownLatch(1)
		val dispatcher = GenerationRunDispatcher(SyncTaskExecutor()) {
			if (attempts.incrementAndGet() == 1) error("database unavailable")
			retried.countDown()
			false
		}

		dispatcher.dispatch()

		assertTrue(retried.await(2, TimeUnit.SECONDS))
		assertEquals(2, attempts.get())
	}
}
