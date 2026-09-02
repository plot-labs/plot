package com.plot.api.routine

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class RoutineScheduleScannerTest {
	@Test
	fun `scan claims every due routine before dispatching once`() {
		val claims = AtomicInteger()
		val worker = mock(RoutineWorker::class.java)
		doAnswer { claims.incrementAndGet() < 3 }.`when`(worker).claimScheduledDue()
		val dispatcher = mock(RoutineRunDispatcher::class.java)
		val scanner = RoutineScheduleScanner(
			worker = worker,
			dispatcher = dispatcher,
			agentProperties = RoutineAgentProperties(workersEnabled = true),
		)

		scanner.scan()

		assertEquals(3, claims.get())
		verify(dispatcher).dispatch()
	}

	@Test
	fun `scanDue claims every due routine without dispatching`() {
		val claims = AtomicInteger()
		val worker = mock(RoutineWorker::class.java)
		doAnswer { claims.incrementAndGet() < 2 }.`when`(worker).claimScheduledDue()
		val dispatcher = mock(RoutineRunDispatcher::class.java)
		val scanner = RoutineScheduleScanner(
			worker = worker,
			dispatcher = dispatcher,
			agentProperties = RoutineAgentProperties(workersEnabled = true),
		)

		assert(scanner.scanDue())

		assertEquals(2, claims.get())
		verify(dispatcher, never()).dispatch()
	}

	@Test
	fun `scan skips work when workers are disabled`() {
		val worker = mock(RoutineWorker::class.java)
		val dispatcher = mock(RoutineRunDispatcher::class.java)
		val scanner = RoutineScheduleScanner(
			worker = worker,
			dispatcher = dispatcher,
			agentProperties = RoutineAgentProperties(workersEnabled = false),
		)

		scanner.scan()

		verify(worker, never()).claimScheduledDue()
		verify(dispatcher, never()).dispatch()
	}

	@Test
	fun `scanDue returns false when no routines are due`() {
		val worker = mock(RoutineWorker::class.java)
		doAnswer { false }.`when`(worker).claimScheduledDue()
		val dispatcher = mock(RoutineRunDispatcher::class.java)
		val scanner = RoutineScheduleScanner(
			worker = worker,
			dispatcher = dispatcher,
			agentProperties = RoutineAgentProperties(workersEnabled = true),
		)

		assertFalse(scanner.scanDue())
		verify(dispatcher, never()).dispatch()
	}
}
