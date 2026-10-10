package com.plot.api.common

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Reads a provider response into a bounded buffer and closes the stream.
 *
 * Responses are streamed rather than handed to an unbounded string handler: if
 * a configured base URL ever stops pointing at the real provider, a hostile
 * host must not be able to exhaust heap with a single large response. Reading
 * stops as soon as the body would exceed [limitBytes], and [tooLarge] supplies
 * the provider-specific error to throw.
 */
internal fun InputStream.readUtf8Capped(limitBytes: Int, tooLarge: () -> RuntimeException): String {
	val buffer = ByteArrayOutputStream(minOf(limitBytes, INITIAL_BUFFER_BYTES))
	val chunk = ByteArray(CHUNK_BYTES)
	use { input ->
		while (true) {
			val read = input.read(chunk)
			if (read < 0) break
			if (buffer.size() + read > limitBytes) throw tooLarge()
			buffer.write(chunk, 0, read)
		}
	}
	return buffer.toString(StandardCharsets.UTF_8)
}

private const val INITIAL_BUFFER_BYTES = 64 * 1024
private const val CHUNK_BYTES = 8_192
