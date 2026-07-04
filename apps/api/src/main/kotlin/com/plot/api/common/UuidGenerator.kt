package com.plot.api.common

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class UuidGenerator(
	private val clock: Clock = Clock.systemUTC(),
) {
	private val random = SecureRandom()

	fun next(): UUID {
		val timestampMillis = clock.millis() and 0x0000FFFFFFFFFFFFL
		val randomA = random.nextLong() and 0xFFFL
		val randomB = random.nextLong() and 0x3FFFFFFFFFFFFFFFL

		val mostSignificantBits =
			(timestampMillis shl 16) or
				(0x7L shl 12) or
				randomA

		val leastSignificantBits =
			(0x2L shl 62) or
				randomB

		return UUID(mostSignificantBits, leastSignificantBits)
	}
}
