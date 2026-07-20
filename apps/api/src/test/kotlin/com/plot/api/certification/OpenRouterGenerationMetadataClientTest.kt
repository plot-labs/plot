package com.plot.api.certification

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

class OpenRouterGenerationMetadataClientTest {
	private val mapper = ObjectMapper()

	@Test
	fun `metadata polling waits for a complete consistent allow-listed projection`() = withServer { server, calls ->
		server.createContext("/api/v1/generation") { exchange ->
			assertEquals("id=gen-persisted-1", exchange.requestURI.rawQuery)
			val attempt = calls.incrementAndGet()
			val payload = when (attempt) {
				1 -> "{\"data\":null}"
				2 -> "{\"data\":{\"id\":\"gen-persisted-1\",\"model\":\"openai/gpt-5.4-nano-2026-06-01\"}}"
				else -> completeMetadata()
			}
			respond(exchange, 200, payload)
		}

		val client = metadataClient(server, maxAttempts = 3)
		val request = GenerationMetadataRequest("gen-persisted-1")
		assertFalse(request.toString().contains("gen-persisted-1"))
		val metadata = client.fetch(request)

		assertEquals(3, calls.get())
		assertEquals("openai/gpt-5.4-nano-2026-06-01", metadata.servedModel)
		assertEquals("openai", metadata.providerSlug)
		assertEquals(12L, metadata.costUsdMicros)
		assertEquals(11, metadata.nativeTokens.prompt)
		assertEquals(7, metadata.nativeTokens.completion)
		assertEquals(3, metadata.nativeTokens.reasoning)
		assertEquals(2, metadata.nativeTokens.cached)
		assertTrue(metadata.responseIdHash.startsWith("sha256:"))
		assertFalse(mapper.writeValueAsString(metadata).contains("gen-persisted-1"))
		assertFalse(mapper.writeValueAsString(metadata).contains("prompt body must stay private"))
	}

	@Test
	fun `poll exhaustion is inconclusive and never changes the persisted response id`() = withServer { server, calls ->
		server.createContext("/api/v1/generation") { exchange ->
			calls.incrementAndGet()
			assertEquals("id=gen-stable", exchange.requestURI.rawQuery)
			respond(exchange, 404, "{\"error\":{\"message\":\"private provider body\"}}")
		}

		val failure = assertFailsWith<CertificationPreflightException> {
			metadataClient(server, maxAttempts = 3).fetch(GenerationMetadataRequest("gen-stable"))
		}
		assertEquals(3, calls.get())
		assertEquals(CertificationDisposition.INCONCLUSIVE, failure.disposition)
		assertEquals(CertificationFailureCode.METADATA_PENDING, failure.code)
		assertFalse(failure.message.orEmpty().contains("gen-stable"))
		assertFalse(failure.message.orEmpty().contains("private provider body"))
	}

	@Test
	fun `provider status classes are safe and never expose response bodies`() = withServer { server, calls ->
		val statuses = listOf(401, 402, 429, 503)
		server.createContext("/api/v1/generation") { exchange ->
			val status = statuses[calls.getAndIncrement()]
			respond(exchange, status, "{\"error\":\"secret response body $status\"}")
		}

		val expected = listOf(
			CertificationDisposition.BLOCKED to CertificationFailureCode.AUTHENTICATION_REJECTED,
			CertificationDisposition.BLOCKED to CertificationFailureCode.QUOTA_EXHAUSTED,
			CertificationDisposition.INCONCLUSIVE to CertificationFailureCode.RATE_LIMITED,
			CertificationDisposition.INCONCLUSIVE to CertificationFailureCode.PROVIDER_UNAVAILABLE,
		)
		expected.forEachIndexed { index, (disposition, code) ->
			val failure = assertFailsWith<CertificationPreflightException> {
				metadataClient(server, maxAttempts = 1).fetch(GenerationMetadataRequest("gen-status-$index"))
			}
			assertEquals(disposition, failure.disposition)
			assertEquals(code, failure.code)
			assertFalse(failure.message.orEmpty().contains("secret response body"))
		}
	}

	private fun metadataClient(server: HttpServer, maxAttempts: Int) = OpenRouterGenerationMetadataClient(
		transport = JdkOpenRouterCertificationTransport.loopbackForTests(
			"http://127.0.0.1:${server.address.port}/api/v1",
			"test-openrouter-secret",
		),
		polling = MetadataPollingPolicy(maxAttempts, Duration.ofSeconds(1), Duration.ZERO),
		sleep = {},
	)

	private fun completeMetadata() = """
		{
		  "data": {
		    "id": "gen-persisted-1",
		    "model": "openai/gpt-5.4-nano-2026-06-01",
		    "provider_name": "OpenAI",
		    "native_tokens_prompt": 11,
		    "native_tokens_completion": 7,
		    "native_tokens_reasoning": 3,
		    "native_tokens_cached": 2,
		    "total_cost": 0.000012,
		    "latency": 123,
		    "generation_time": 101,
		    "finish_reason": "stop",
		    "is_byok": false,
		    "upstream_id": "upstream-private-123",
		    "provider_responses": null,
		    "prompt": "prompt body must stay private",
		    "usage": {"private": "unknown fields must not escape"}
		  }
		}
	""".trimIndent()

	private fun withServer(block: (HttpServer, AtomicInteger) -> Unit) {
		val calls = AtomicInteger()
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.start()
		try {
			block(server, calls)
		} finally {
			server.stop(0)
		}
	}

	private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
		val bytes = body.toByteArray(StandardCharsets.UTF_8)
		exchange.responseHeaders.add("Content-Type", "application/json")
		exchange.sendResponseHeaders(status, bytes.size.toLong())
		exchange.responseBody.use { it.write(bytes) }
	}
}
