package com.plot.api.chat

import kotlin.test.*
import org.junit.jupiter.api.Test

class WriterDraftProjectorTest {
	@Test
	fun `only complete direct sentence bodies appear across arbitrary unicode and escaped splits`() {
		val json = """{"body":"secret","nested":{"sentences":[{"body":"secret"}]},"sentences":[{"intent":"FACTUAL","nested":{"body":"secret"},"body":"안녕 😀 \"인용\"\n둘째"},{"body":"두 번째","id":"private"}],"layout":[]}"""
		for (size in 1..json.length) {
			val snapshots = mutableListOf<List<String>>()
			WriterDraftProjector(snapshots::add).use { projector ->
				json.chunked(size).forEach(projector::accept)
				projector.finish()
			}
			assertEquals(listOf(listOf("안녕 😀 \"인용\"\n둘째"), listOf("안녕 😀 \"인용\"\n둘째", "두 번째")), snapshots)
		}
	}

	@Test
	fun `incomplete body never appears and invalid preview does not abort provider consumption`() {
		val snapshots = mutableListOf<List<String>>()
		WriterDraftProjector(snapshots::add).use { projector ->
			projector.accept("""{"sentences":[{"body":"first"},{"body":"incomplete""")
			assertEquals(listOf(listOf("first")), snapshots)
			projector.finish()
			projector.accept("more malformed data")
		}
		assertEquals(listOf(listOf("first")), snapshots)
	}

	@Test
	fun `duplicate body replaces same position and observer failures propagate`() {
		val snapshots = mutableListOf<List<String>>()
		WriterDraftProjector(snapshots::add).use { it.accept("""{"sentences":[{"body":"first","body":"corrected"}]}"""); it.finish() }
		assertEquals(listOf(listOf("first"),listOf("corrected")), snapshots)
		WriterDraftProjector { throw IllegalStateException("lost claim") }.use { projector ->
			assertFailsWith<IllegalStateException> { projector.accept("""{"sentences":[{"body":"first"}]}""") }
		}
	}
}
