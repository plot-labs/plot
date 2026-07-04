package com.plot.api.common

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
		val generator = UuidGenerator()

		val first = generator.next()
		Thread.sleep(2)
		val second = generator.next()

		assertTrue(first.toString() < second.toString())
	}
}
