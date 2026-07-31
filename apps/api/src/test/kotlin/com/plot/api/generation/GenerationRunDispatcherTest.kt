package com.plot.api.generation

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
	fun `unexpected drain failure stops the current dispatch turn`() {
		val attempts = AtomicInteger()
		val retried = CountDownLatch(1)
		val dispatcher = GenerationRunDispatcher(SyncTaskExecutor()) {
			if (attempts.incrementAndGet() == 1) error("database unavailable")
			retried.countDown()
			false
		}

		assertFailsWith<IllegalStateException> { dispatcher.dispatch() }

		assertFalse(retried.await(1200, TimeUnit.MILLISECONDS))
		assertEquals(1, attempts.get())
	}
}
