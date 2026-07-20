package com.plot.api.ai.provider

import com.openai.client.OpenAIClientImpl
import com.openai.client.OpenAIClientAsyncImpl
import com.openai.core.ClientOptions
import com.plot.api.ai.prompt.ChangelogPrompt
import com.plot.api.config.PlotAiProperties
import com.plot.api.generation.model.WriterOutput
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient
import tools.jackson.databind.ObjectMapper

class OpenRouterWireContractTest {
	private val mapper = ObjectMapper()

	@Test
	fun `Spring AI serializes the OpenRouter route and safe headers on the wire`() {
		val requestBody = AtomicReference<String>()
		val metadataHeader = AtomicReference<String>()
		val titleHeader = AtomicReference<String>()
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/api/v1/chat/completions") { exchange ->
			requestBody.set(exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
			metadataHeader.set(exchange.requestHeaders.getFirst("X-OpenRouter-Metadata"))
			titleHeader.set(exchange.requestHeaders.getFirst("X-OpenRouter-Title"))
			val response = """
				{"id":"gen-wire","object":"chat.completion","created":1784160000,"model":"openai/gpt-5.4-nano-2026-06-01","choices":[{"index":0,"message":{"role":"assistant","content":"{\"sentences\":[{\"body\":\"Wire contract passed.\"}]}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18},"openrouter_metadata":{"requested":"openai/gpt-5.4-nano","unknown_content":"must not escape"}}
			""".trimIndent().toByteArray()
			exchange.responseHeaders.add("Content-Type", "application/json")
			exchange.sendResponseHeaders(200, response.size.toLong())
			exchange.responseBody.use { it.write(response) }
		}
		server.start()
		val baseUrl = "http://127.0.0.1:${server.address.port}/api/v1"
		val testProperties = properties(baseUrl = baseUrl, enabled = false)
		val httpClient = SpringAiOpenAiHttpClient.builder().timeout(testProperties.timeout).build()
		val clientOptions = ClientOptions.builder()
			.httpClient(httpClient)
			.apiKey("test-key")
			.baseUrl(baseUrl)
			.maxRetries(0)
			.build()
		val client = OpenAIClientImpl(clientOptions)
		val asyncClient = OpenAIClientAsyncImpl(clientOptions)

		try {
			val model = OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(asyncClient).build()
			val response = SpringAiStructuredChatTransport(ChatClient.builder(model), testProperties).exchange(
				StructuredChatRequest(ModelRole.WRITER, ChangelogPrompt("System", "User")),
				WriterOutput::class.java,
			)
			val body = mapper.readTree(requestBody.get())
			assertEquals("openai/gpt-5.4-nano", body["model"].stringValue())
			assertEquals(listOf("openai"), body["provider"]["order"].asArray().values().map { it.stringValue() })
			assertEquals(listOf("openai"), body["provider"]["only"].asArray().values().map { it.stringValue() })
			assertFalse(body["provider"]["allow_fallbacks"].booleanValue())
			assertFalse(body["provider"]["require_parameters"].booleanValue())
			assertEquals("deny", body["provider"]["data_collection"].stringValue())
			assertFalse(body["provider"]["zdr"].booleanValue())
			assertFalse(body.has("temperature"))
			assertEquals(4_000, body["max_completion_tokens"].intValue())
			assertEquals("enabled", metadataHeader.get())
			assertEquals("Plot", titleHeader.get())
			assertEquals("gen-wire", response.responseId)
			assertEquals("openai/gpt-5.4-nano-2026-06-01", response.actualModel)
			assertEquals("Wire contract passed.", response.value.sentences.single().body)
		} finally {
			client.close()
			asyncClient.close()
			server.stop(0)
		}
	}

	@Test
	fun `nano profile sends one pinned route without temperature`() {
		val transport = transport(properties(model = "openai/gpt-5.4-nano"))

		ModelRole.entries.forEach { role ->
			val options = transport.optionsFor(role) as OpenAiChatOptions
			assertEquals("https://openrouter.ai/api/v1", options.baseUrl)
			assertEquals("openai/gpt-5.4-nano", options.model)
			assertNull(options.temperature)
			assertEquals(4_000, options.maxCompletionTokens)
			assertEquals(0, options.maxRetries)
			assertEquals(mapOf("X-OpenRouter-Metadata" to "enabled", "X-OpenRouter-Title" to "Plot"), options.customHeaders)
			val extraBody = requireNotNull(options.extraBody)
			assertEquals(
				mapOf(
					"order" to listOf("openai"),
					"only" to listOf("openai"),
					"allow_fallbacks" to false,
					"require_parameters" to false,
					"data_collection" to "deny",
					"zdr" to false,
				),
				extraBody["provider"],
			)
			assertFalse(extraBody.containsKey("models"))
			assertEquals(ModelSchemas.forRole(role), options.outputSchema)
			assertTrue(options.toolCallbacks.orEmpty().isEmpty())
		}
	}

	@Test
	fun `pinned GPT-4o Mini profile retains role temperatures`() {
		val transport = transport(properties(model = "openai/gpt-4o-mini-2024-07-18"))

		assertEquals(0.2, (transport.optionsFor(ModelRole.WRITER) as OpenAiChatOptions).temperature)
		assertEquals(0.0, (transport.optionsFor(ModelRole.REVIEWER) as OpenAiChatOptions).temperature)
		assertEquals(0.2, (transport.optionsFor(ModelRole.REWRITER) as OpenAiChatOptions).temperature)
	}

	@Test
	fun `unsafe OpenRouter configuration fails before a model call`() {
		listOf(
			{ properties(routingProvider = null) },
			{ properties(allowFallbacks = true) },
			{ properties(baseUrl = "https://api.openai.com/v1") },
			{ properties(contentLoggingEnabled = true) },
			{ properties(provider = "openai") },
		).forEach { unsafe -> assertFailsWith<IllegalArgumentException> { unsafe() } }
	}

	private fun transport(properties: PlotAiProperties) = SpringAiStructuredChatTransport(
		ChatClient.builder { throw UnsupportedOperationException("wire call is not expected") },
		properties,
	)

	private fun properties(
		enabled: Boolean = true,
		provider: String = "openrouter",
		model: String = "openai/gpt-5.4-nano",
		baseUrl: String = "https://openrouter.ai/api/v1",
		routingProvider: String? = "openai",
		allowFallbacks: Boolean = false,
		contentLoggingEnabled: Boolean = false,
	) = PlotAiProperties(
		enabled = enabled,
		provider = provider,
		model = model,
		baseUrl = baseUrl,
		routingProvider = routingProvider,
		allowFallbacks = allowFallbacks,
		contentLoggingEnabled = contentLoggingEnabled,
	)
}
