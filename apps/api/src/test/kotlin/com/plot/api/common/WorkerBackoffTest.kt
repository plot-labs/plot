package com.plot.api.common

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkerBackoffTest {
	private val initial = Duration.ofSeconds(5)

	@Test
	fun doublesTheInitialDelayPerExponent() {
		assertEquals(Duration.ofSeconds(5), WorkerBackoff.delay(initial, 0))
		assertEquals(Duration.ofSeconds(10), WorkerBackoff.delay(initial, 1))
		assertEquals(Duration.ofSeconds(40), WorkerBackoff.delay(initial, 3))
	}

	@Test
	fun treatsNegativeExponentsAsTheFirstAttempt() {
		assertEquals(initial, WorkerBackoff.delay(initial, -1))
	}

	@Test
	fun capsTheDelayAtFifteenMinutes() {
		assertEquals(Duration.ofSeconds(640), WorkerBackoff.delay(initial, 7))
		assertEquals(Duration.ofMinutes(15), WorkerBackoff.delay(initial, 8))
		assertEquals(Duration.ofMinutes(15), WorkerBackoff.delay(initial, 1_000))
	}

	@Test
	fun clampsTheShiftSoSmallInitialDelaysStopGrowing() {
		val small = Duration.ofMillis(250)
		assertEquals(Duration.ofMillis(64_000), WorkerBackoff.delay(small, 8))
		assertEquals(Duration.ofMillis(64_000), WorkerBackoff.delay(small, 60))
	}
}
