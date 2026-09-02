package com.plot.api.common

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

class WorkerWakeupTest {
	private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
	private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

	@Test
	fun `dispatches once the scheduled retry becomes due`() {
		val executor = Executors.newSingleThreadScheduledExecutor()
		try {
			val dispatched = CountDownLatch(1)
			val wakeup = WorkerWakeup(executor, clock) { dispatched.countDown() }

			wakeup.scheduleAt(now.plusMillis(50))

			assertTrue(dispatched.await(2, TimeUnit.SECONDS))
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `keeps a single armed timer for later retries`() {
		val executor = Executors.newSingleThreadScheduledExecutor()
		try {
			val dispatches = AtomicInteger()
			val wakeup = WorkerWakeup(executor, clock) { dispatches.incrementAndGet() }

			wakeup.scheduleAt(now.plusMillis(50))
			wakeup.scheduleAt(now.plusMillis(5_000))
			wakeup.scheduleAt(now.plusMillis(10_000))

			Thread.sleep(500)
			assertEquals(1, dispatches.get())
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `an earlier retry replaces a later armed timer`() {
		val executor = Executors.newSingleThreadScheduledExecutor()
		try {
			val dispatches = AtomicInteger()
			val wakeup = WorkerWakeup(executor, clock) { dispatches.incrementAndGet() }

			wakeup.scheduleAt(now.plusMillis(30_000))
			wakeup.scheduleAt(now.plusMillis(50))

			Thread.sleep(500)
			assertEquals(1, dispatches.get())
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `a superseded later timer does not dispatch again`() {
		val executor = Executors.newSingleThreadScheduledExecutor()
		try {
			val dispatches = AtomicInteger()
			val wakeup = WorkerWakeup(executor, clock) { dispatches.incrementAndGet() }

			wakeup.scheduleAt(now.plusMillis(200))
			Thread.sleep(25)
			wakeup.scheduleAt(now.plusMillis(50))
			Thread.sleep(500)

			assertEquals(1, dispatches.get())
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `no wakeup is armed without a pending retry or executor`() {
		val executor = Executors.newSingleThreadScheduledExecutor()
		try {
			val dispatches = AtomicInteger()
			WorkerWakeup(executor, clock) { dispatches.incrementAndGet() }.scheduleAt(null)
			WorkerWakeup(null, clock) { dispatches.incrementAndGet() }.scheduleAt(now.plusMillis(20))

			Thread.sleep(300)
			assertEquals(0, dispatches.get())
		} finally {
			executor.shutdownNow()
		}
	}
}
