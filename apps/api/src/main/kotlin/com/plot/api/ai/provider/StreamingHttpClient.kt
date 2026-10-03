package com.plot.api.ai.provider

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Koog 1.2 Java SSE drops full-channel trySend results; keep its codecs with lossless emit. */
internal class StreamingHttpClient(
	private val delegate: KoogHttpClient,
	private val baseUrl: String,
	private val headers: Map<String, String>,
	private val timeout: Duration,
) : KoogHttpClient by delegate {
	private val http = HttpClient.newBuilder().connectTimeout(minOf(timeout, Duration.ofSeconds(10))).build()

	override fun <T : Any, R : Any, O : Any> sse(
		path: String, requestBody: T, requestBodyType: KClass<T>, dataFilter: (String?) -> Boolean,
		decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
		parameters: Map<String, String>, headers: Map<String, String>,
	): Flow<O> = flow {
		require(requestBody is String && parameters.isEmpty()) { "Expected Koog serialized chat request" }
		coroutineScope {
			val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/${path.trimStart('/')}"))
				.timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(requestBody))
			(this@StreamingHttpClient.headers + mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream") + headers)
				.forEach { (key, value) -> request.header(key, value) }
			val future = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
			val response = suspendCancellableCoroutine<HttpResponse<java.io.InputStream>> { continuation ->
				continuation.invokeOnCancellation { future.cancel(true) }
				future.whenComplete { value, error ->
					if (error != null) { if (continuation.isActive) continuation.resumeWithException(error) }
					else continuation.resume(value) { _, result, _ -> result.body().close() }
				}
			}
			val closer = launch(start = CoroutineStart.UNDISPATCHED) {
				try { awaitCancellation() } finally { response.body().close() }
			}
			try {
				if (response.statusCode() !in 200..299) throw KoogHttpClientException(clientName, statusCode = response.statusCode())
				if (!response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
					throw MalformedModelOutputException("Expected provider stream")
				}
				val reader = response.body().bufferedReader(Charsets.UTF_8)
				val record = StringBuilder()
				while (true) {
					ensureActive()
					val line = reader.readBoundedLine() ?: throw MalformedModelOutputException("Provider stream ended early")
					if (line.length + record.length > MAX_RECORD_CHARACTERS) throw MalformedModelOutputException("Provider frame exceeded limit")
					if (line.isEmpty()) {
						if (record.isEmpty()) continue
						val data = record.toString().removeSuffix("\n")
						record.setLength(0)
						if (data == "[DONE]") break
						if (dataFilter(data)) processStreamingChunk(decodeStreamingResponse(data))?.let { emit(it) }
					} else if (line.startsWith("data:")) record.append(line.substring(5).removePrefix(" ")).append('\n')
				}
			} finally { closer.cancelAndJoin(); response.body().close() }
		}
	}.flowOn(Dispatchers.IO)

	private fun java.io.BufferedReader.readBoundedLine(): String? {
		val line = StringBuilder()
		while (true) {
			val next = read()
			if (next == -1) return if (line.isEmpty()) null else line.toString().removeSuffix("\r")
			if (next == '\n'.code) return line.toString().removeSuffix("\r")
			if (line.length >= MAX_RECORD_CHARACTERS) throw MalformedModelOutputException("Provider frame exceeded limit")
			line.append(next.toChar())
		}
	}

	override fun close() { http.shutdownNow(); delegate.close() }

	private companion object { const val MAX_RECORD_CHARACTERS = 1_000_000 }
}
