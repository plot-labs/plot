package com.plot.api.chat

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonToken
import tools.jackson.core.ObjectReadContext
import tools.jackson.core.async.ByteArrayFeeder
import tools.jackson.core.json.JsonFactory

/** Preview only: malformed JSON stops projection; normal final validation and usage still run. */
internal class WriterDraftProjector(private val onParagraphs: (List<String>) -> Unit) : AutoCloseable {
	private val parser = JsonFactory().createNonBlockingByteArrayParser(ObjectReadContext.empty())
	private val feeder = parser.nonBlockingInputFeeder() as ByteArrayFeeder
	private val bodies = sortedMapOf<Int, String>()
	private var disabled = false
	private var trailingSurrogate = ""
	private var length = 0

	fun accept(delta: String) {
		if (disabled) return
		length += delta.length
		if (length > 400_000) { disabled = true; return }
		val text = trailingSurrogate + delta
		trailingSurrogate = if (text.lastOrNull()?.isHighSurrogate() == true) text.takeLast(1) else ""
		val bytes = text.dropLast(trailingSurrogate.length).toByteArray(Charsets.UTF_8)
		if (bytes.isEmpty()) return
		project { feeder.feedInput(bytes, 0, bytes.size) }
	}

	fun finish() {
		if (!disabled) project { feeder.endOfInput() }
		disabled = true
	}

	private fun project(feed: () -> Unit) {
		val completed = mutableListOf<Pair<Int, String>>()
		try {
			feed()
			while (true) {
				val token = parser.nextToken() ?: break
				if (token == JsonToken.NOT_AVAILABLE) break
				if (token != JsonToken.VALUE_STRING) continue
				val field = parser.streamReadContext()
				val array = field.parent ?: continue
				val objectRoot = array.parent ?: continue
				val root = objectRoot.parent ?: continue
				if (field.inObject() && field.currentName() == "body" && array.inArray() &&
					objectRoot.inObject() && objectRoot.currentName() == "sentences" && root.inRoot() && root.currentIndex == 0) {
					completed.add(array.currentIndex to parser.string)
				}
			}
		} catch (_: JacksonException) { disabled = true }
		// Keep storage/lease observer failures outside the preview parser's error boundary.
		for ((index, body) in completed) {
			bodies[index] = body
			onParagraphs(bodies.values.toList())
		}
	}

	override fun close() { disabled = true; parser.close() }
}
