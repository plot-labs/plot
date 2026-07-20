package com.plot.api.certification

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenRouterPreflightTest {
	@Test
	fun `one eligible endpoint and one production canary seal only after authoritative attribution`() = withServer { server ->
		val inventoryCalls = AtomicInteger()
		val metadataCalls = AtomicInteger()
		val authorization = AtomicReference<String>()
		server.createContext("/api/v1/models/openai/gpt-5.4-nano/endpoints") { exchange ->
			inventoryCalls.incrementAndGet()
			authorization.set(exchange.requestHeaders.getFirst("Authorization"))
			respond(exchange, 200, eligibleInventory())
		}
		server.createContext("/api/v1/endpoints/zdr") { exchange -> respond(exchange, 200, eligibleZdrInventory()) }
		server.createContext("/api/v1/generation") { exchange ->
			metadataCalls.incrementAndGet()
			respond(exchange, 200, completeMetadata())
		}
		val canaryCalls = AtomicInteger()
		val sealCalls = AtomicInteger()
		val preflight = preflight(server)

		val result = preflight.run(
			OpenRouterPreflightRequest("openai/gpt-5.4-nano", "openai"),
			ProductionStructuredCanary {
				canaryCalls.incrementAndGet()
				ProductionCanaryResult(modelMetadata(), structuredOutputParsed = true)
			},
		) { verified ->
			sealCalls.incrementAndGet()
			assertEquals("openai/gpt-5.4-nano-2026-06-01", verified.servedModel)
			assertEquals("openai", verified.providerSlug)
			"sealed-model-execution"
		}

		assertEquals(1, inventoryCalls.get())
		assertEquals(1, canaryCalls.get())
		assertEquals(1, metadataCalls.get())
		assertEquals(1, sealCalls.get())
		assertEquals("Bearer test-openrouter-secret", authorization.get())
		assertEquals("sealed-model-execution", result.sealedModelExecution)
		assertEquals("openai/gpt-5.4-nano-2026-06-01", result.attribution.servedModel)
		assertTrue(result.attribution.responseIdHash.startsWith("sha256:"))
	}

	@Test
	fun `disabled ZDR skips ZDR inventory while retaining the pinned structured route`() = withServer { server ->
		server.createContext("/api/v1/models/openai/gpt-5.4-nano/endpoints") { exchange ->
			respond(exchange, 200, eligibleInventory())
		}
		val client = OpenRouterCertificationClient(
			JdkOpenRouterCertificationTransport.loopbackForTests(
				"http://127.0.0.1:${server.address.port}/api/v1",
				"test-openrouter-secret",
			),
		)

		val result = client.selectEndpoint(
			OpenRouterPreflightRequest("openai/gpt-5.4-nano", "openai", requireZeroDataRetention = false),
		)

		assertEquals("openai/gpt-5.4-nano", result.requestedModel)
		assertEquals("openai", result.providerSlug)
	}

	@Test
	fun `provider-confirmed model alias may be sealed when canary and metadata agree`() = withServer { server ->
		server.createContext("/api/v1/models/openai/gpt-5.4-nano/endpoints") { exchange ->
			respond(exchange, 200, eligibleInventory())
		}
		server.createContext("/api/v1/endpoints/zdr") { exchange -> respond(exchange, 200, eligibleZdrInventory()) }
		server.createContext("/api/v1/generation") { exchange -> respond(exchange, 200, completeMetadata()) }
		val canaryCalls = AtomicInteger()
		val sealCalls = AtomicInteger()

		val result = preflight(server).run(
			OpenRouterPreflightRequest("openai/gpt-5.4-nano", "openai"),
			ProductionStructuredCanary {
				canaryCalls.incrementAndGet()
				ProductionCanaryResult(modelMetadata(servedModel = "openai/gpt-5.4-nano"), true)
			},
		) {
			sealCalls.incrementAndGet()
			"sealed-alias"
		}

		assertEquals("openai/gpt-5.4-nano-2026-06-01", result.attribution.servedModel)
		assertEquals("sealed-alias", result.sealedModelExecution)
		assertEquals(1, canaryCalls.get())
		assertEquals(1, sealCalls.get())
	}

	@Test
	fun `ambiguous non ZDR unsupported and expired inventories fail closed`() = withServer { server ->
		val payloads = listOf(
			eligibleInventory(extraEndpoint = true),
			eligibleInventory(),
			eligibleInventory(structured = false),
			eligibleInventory(expiration = "2026-01-01T00:00:00Z"),
		)
		val calls = AtomicInteger()
		server.createContext("/api/v1/models/openai/gpt-5.4-nano/endpoints") { exchange ->
			respond(exchange, 200, payloads[calls.getAndIncrement()])
		}
		val zdrCalls = AtomicInteger()
		server.createContext("/api/v1/endpoints/zdr") { exchange ->
			respond(exchange, 200, if (zdrCalls.getAndIncrement() == 0) "{\"data\":[]}" else eligibleZdrInventory())
		}

		val expected = listOf(
			CertificationFailureCode.AMBIGUOUS_ENDPOINT,
			CertificationFailureCode.NO_ZDR_ENDPOINT,
			CertificationFailureCode.STRUCTURED_OUTPUT_UNSUPPORTED,
			CertificationFailureCode.MODEL_EXPIRED,
		)
		expected.forEach { code ->
			val failure = assertFailsWith<CertificationPreflightException> {
				preflight(server).run(
					OpenRouterPreflightRequest("openai/gpt-5.4-nano", "openai"),
					ProductionStructuredCanary { error("canary must not run") },
				) { error("seal must not run") }
			}
			assertEquals(code, failure.code)
		}
	}

	@Test
	fun `canary metadata mismatch and pending metadata never repeat the model call`() = withServer { server ->
		server.createContext("/api/v1/models/openai/gpt-5.4-nano/endpoints") { exchange ->
			respond(exchange, 200, eligibleInventory())
		}
		server.createContext("/api/v1/endpoints/zdr") { exchange -> respond(exchange, 200, eligibleZdrInventory()) }
		val metadataCalls = AtomicInteger()
		server.createContext("/api/v1/generation") { exchange ->
			metadataCalls.incrementAndGet()
			respond(exchange, 404, "{\"error\":\"not ready\"}")
		}
		val canaryCalls = AtomicInteger()
		val preflight = preflight(server, maxMetadataAttempts = 3)

		val failure = assertFailsWith<CertificationPreflightException> {
			preflight.run(
				OpenRouterPreflightRequest("openai/gpt-5.4-nano", "openai"),
				ProductionStructuredCanary {
					canaryCalls.incrementAndGet()
					ProductionCanaryResult(modelMetadata(), true)
				},
			) { error("seal must not run") }
		}

		assertEquals(CertificationFailureCode.METADATA_PENDING, failure.code)
		assertEquals(1, canaryCalls.get())
		assertEquals(3, metadataCalls.get())
	}

	@Test
	fun `served model and provider must match inventory canary and metadata`() = withServer { server ->
		server.createContext("/api/v1/models/openai/gpt-5.4-nano/endpoints") { exchange ->
			respond(exchange, 200, eligibleInventory())
		}
		server.createContext("/api/v1/endpoints/zdr") { exchange -> respond(exchange, 200, eligibleZdrInventory()) }
		server.createContext("/api/v1/generation") { exchange ->
			respond(exchange, 200, completeMetadata(provider = "other"))
		}

		val failure = assertFailsWith<CertificationPreflightException> {
			preflight(server).run(
				OpenRouterPreflightRequest("openai/gpt-5.4-nano", "openai"),
				ProductionStructuredCanary { ProductionCanaryResult(modelMetadata(), true) },
			) { error("seal must not run") }
		}

		assertEquals(CertificationFailureCode.ATTRIBUTION_MISMATCH, failure.code)
		assertFalse(failure.message.orEmpty().contains("other"))
	}

	private fun preflight(
		server: HttpServer,
		maxMetadataAttempts: Int = 1,
	): OpenRouterPreflight {
		val transport = JdkOpenRouterCertificationTransport.loopbackForTests(
			"http://127.0.0.1:${server.address.port}/api/v1",
			"test-openrouter-secret",
		)
		return OpenRouterPreflight(
			OpenRouterCertificationClient(transport),
			OpenRouterGenerationMetadataClient(
				transport,
				MetadataPollingPolicy(maxMetadataAttempts, Duration.ofSeconds(1), Duration.ZERO),
				sleep = {},
			),
		)
	}

	@Test
	fun `officially valid keys allow key types unlimited balances and absent expiry`() {
		val accepted = listOf(
			eligibleCurrentKey().replace("\"is_management_key\":false", "\"is_management_key\":true"),
			eligibleCurrentKey().replace("\"is_provisioning_key\":false", "\"is_provisioning_key\":true"),
			eligibleCurrentKey().replace("\"is_free_tier\":false", "\"is_free_tier\":true"),
			eligibleCurrentKey().replace("\"limit\":10.0", "\"limit\":null").replace("\"limit_remaining\":9.0", "\"limit_remaining\":null"),
			eligibleCurrentKey().replace("\"expires_at\":\"2099-12-31T23:59:59Z\"", "\"expires_at\":null"),
			eligibleCurrentKey().replace("\"limit_remaining\":9.0", "\"limit_remaining\":0.5"),
		)
		accepted.forEach { payload ->
			val isolated = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
			isolated.createContext("/api/v1/key") { exchange -> respond(exchange, 200, payload) }
			isolated.createContext("/api/v1/models/openai/gpt-5.4-nano/endpoints") { exchange -> respond(exchange, 200, eligibleInventory()) }
			isolated.createContext("/api/v1/endpoints/zdr") { exchange -> respond(exchange, 200, eligibleZdrInventory()) }
			isolated.start()
			try {
				val verified = OpenRouterCertificationClient(
					JdkOpenRouterCertificationTransport.loopbackForTests(
						"http://127.0.0.1:${isolated.address.port}/api/v1",
						"test-openrouter-secret",
					),
				).selectEndpoint(OpenRouterPreflightRequest("openai/gpt-5.4-nano", "openai"))
				assertEquals("openai/gpt-5.4-nano", verified.requestedModel)
			} finally {
				isolated.stop(0)
			}
		}
	}

	@Test
	fun `exhausted finite key is rejected before inventory`() = withServer { server ->
		server.removeContext("/api/v1/key")
		server.createContext("/api/v1/key") { exchange ->
			respond(exchange, 200, eligibleCurrentKey().replace("\"limit_remaining\":9.0", "\"limit_remaining\":0.0"))
		}

		val failure = assertFailsWith<CertificationPreflightException> {
			preflight(server).run(
				OpenRouterPreflightRequest("openai/gpt-5.4-nano", "openai"),
				ProductionStructuredCanary { error("canary must not run") },
			) { error("seal must not run") }
		}
		assertEquals(CertificationFailureCode.QUOTA_EXHAUSTED, failure.code)
	}

	private fun modelMetadata(servedModel: String = "openai/gpt-5.4-nano-2026-06-01") = ProductionCanaryMetadata(
		responseId = "gen-canary-1",
		servedModel = servedModel,
		gateway = "openrouter",
		requestedModel = "openai/gpt-5.4-nano",
	)

	private fun eligibleInventory(
		structured: Boolean = true,
		expiration: String? = null,
		extraEndpoint: Boolean = false,
	): String {
		val endpoint = """
			{
			  "provider_name": "OpenAI",
			  "model_name": "GPT-5.4 Nano",
			  "tag": "openai",
			  "supported_parameters": [${if (structured) "\"response_format\"" else "\"temperature\""}],
			  "expiration_date": ${expiration?.let { "\"$it\"" } ?: "null"}
			}
		""".trimIndent()
		return """
			{
			  "data": {
			    "id": "openai/gpt-5.4-nano",
			    "endpoints": [$endpoint${if (extraEndpoint) ",$endpoint" else ""}]
			  }
			}
		""".trimIndent()
	}

	private fun eligibleCurrentKey() = """
		{"data":{"label":"<redacted>","limit":10.0,"limit_remaining":9.0,"is_free_tier":false,
		"is_management_key":false,"is_provisioning_key":false,"expires_at":"2099-12-31T23:59:59Z"}}
	""".trimIndent()

	private fun eligibleZdrInventory() = """
		{"data":[{"model_id":"openai/gpt-5.4-nano","model_name":"GPT-5.4 Nano","provider_name":"OpenAI","tag":"openai","supported_parameters":["response_format"]}]}
	""".trimIndent()

	private fun completeMetadata(
		provider: String = "openai",
		model: String = "openai/gpt-5.4-nano-2026-06-01",
	) = """
		{
		  "data": {
		    "id": "gen-canary-1",
		    "model": "$model",
		    "provider_name": "${if (provider == "openai") "OpenAI" else provider}",
		    "native_tokens_prompt": 11,
		    "native_tokens_completion": 7,
		    "native_tokens_reasoning": 3,
		    "native_tokens_cached": 2,
		    "total_cost": 0.000012,
		    "latency": 123,
		    "generation_time": 101,
		    "finish_reason": "stop",
		    "is_byok": false,
		    "upstream_id": "private-upstream-id",
		    "provider_responses": null
		  }
		}
	""".trimIndent()

	private fun withServer(block: (HttpServer) -> Unit) {
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/api/v1/key") { exchange -> respond(exchange, 200, eligibleCurrentKey()) }
		server.start()
		try {
			block(server)
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
