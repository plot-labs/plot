package com.plot.api.common

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UuidGeneratorTest {

	@Test
	fun generatedIdsUseUuidVersion7() {
		val generator = UuidGenerator()

		val id = generator.next()

		assertEquals(7, id.version())
		assertEquals(2, id.variant())
	}

	@Test
	fun generatedIdsAreMostlyTimeOrdered() {
		val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
		val generator = UuidGenerator(clock)

		val first = generator.next()
		clock.tickTo(Instant.parse("2026-01-01T00:00:00.001Z"))
		val second = generator.next()

		assertTrue(first.toString() < second.toString())
	}

	private class MutableClock(
		private var currentInstant: Instant,
		private val currentZone: ZoneId = ZoneId.of("UTC"),
	) : Clock() {

		override fun getZone(): ZoneId = currentZone

		override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

		override fun instant(): Instant = currentInstant

		fun tickTo(instant: Instant) {
			currentInstant = instant
		}
	}
}
