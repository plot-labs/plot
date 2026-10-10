package com.plot.api.common

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkerExecutorsTest {
	@Test
	fun taskExecutorRunsOneTurnAndQueuesOneMore() {
		val executor = WorkerExecutors.singleSlotTaskExecutor("plot-test-")

		assertEquals(1, executor.corePoolSize)
		assertEquals(1, executor.maxPoolSize)
		assertEquals(1, executor.queueCapacity)
		assertEquals("plot-test-", executor.threadNamePrefix)
	}

	@Test
	fun schedulerUsesANamedDaemonThread() {
		val scheduler = WorkerExecutors.daemonScheduler("plot-test-retry")
		try {
			val thread = AtomicReference<Thread>()
			val ran = CountDownLatch(1)
			scheduler.execute {
				thread.set(Thread.currentThread())
				ran.countDown()
			}

			assertTrue(ran.await(5, TimeUnit.SECONDS))
			assertEquals("plot-test-retry", thread.get().name)
			assertTrue(thread.get().isDaemon)
		} finally {
			scheduler.shutdownNow()
		}
	}
}
