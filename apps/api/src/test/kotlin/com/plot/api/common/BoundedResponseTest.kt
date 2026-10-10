package com.plot.api.common

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoundedResponseTest {
	private val tooLarge = IllegalStateException("too large")

	@Test
	fun readsABodyUpToTheLimit() {
		val body = "a".repeat(20_000)

		assertEquals(body, body.byteInputStream().readUtf8Capped(20_000) { tooLarge })
	}

	@Test
	fun decodesUtf8() {
		assertEquals("한글 ok", "한글 ok".byteInputStream().readUtf8Capped(1_024) { tooLarge })
	}

	@Test
	fun throwsTheSuppliedErrorOnceTheBodyExceedsTheLimit() {
		val failure = assertThrows(IllegalStateException::class.java) {
			"a".repeat(20_001).byteInputStream().readUtf8Capped(20_000) { tooLarge }
		}

		assertSame(tooLarge, failure)
	}

	@Test
	fun closesTheStreamOnSuccessAndOnFailure() {
		val ok = TrackingStream(ByteArray(10))
		ok.readUtf8Capped(10) { tooLarge }
		assertTrue(ok.closed)

		val oversized = TrackingStream(ByteArray(11))
		assertThrows(IllegalStateException::class.java) { oversized.readUtf8Capped(10) { tooLarge } }
		assertTrue(oversized.closed)
	}

	private class TrackingStream(bytes: ByteArray) : InputStream() {
		private val delegate = ByteArrayInputStream(bytes)
		var closed = false

		override fun read(): Int = delegate.read()
		override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
		override fun close() {
			closed = true
		}
	}
}
