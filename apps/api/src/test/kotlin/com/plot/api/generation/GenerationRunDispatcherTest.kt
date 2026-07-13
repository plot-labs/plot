package com.plot.api.generation

import java.util.concurrent.atomic.AtomicInteger
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
}
