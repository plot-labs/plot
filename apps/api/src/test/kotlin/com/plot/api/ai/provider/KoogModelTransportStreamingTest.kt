package com.plot.api.ai.provider

import ai.koog.http.client.java.JavaKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import com.plot.api.config.PlotAiProperties
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlin.test.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class KoogModelTransportStreamingTest {
	private val mapper = jacksonObjectMapper()

	@Test
	fun `text preserves metadata and duplicate finish usage is counted once`() = provider(listOf(
		chunk("{\"content\":\"안녕 \"}"), chunk("{\"content\":\"world\"}", "stop"),
		chunk("{}", "stop", """, "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"cost":0.001,"completion_tokens_details":{"reasoning_tokens":1}}"""), "[DONE]",
	)) { transport, request ->
		val result = runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList()) }
		assertTrue(mapper.readTree(request()).path("stream").booleanValue())
		assertEquals("안녕 world", result.message.textContent())
		assertEquals("gen-test", result.usage.responseId)
		assertEquals("served-test", result.usage.actualModel)
		assertEquals(10L, result.usage.totalTokens)
		assertEquals("0.001", result.usage.reportedCostUsd?.toPlainString())
	}

	@Test
	fun `tool arguments are assembled before a native call is returned`() = provider(listOf(
		chunk("""{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"GET_SKILL","arguments":"{\"skillId\":"}}]}"""),
		chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"\"abc\"}"}}]}""", "tool_calls"), "[DONE]",
	)) { transport, _ ->
		val result = runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList()) }
		val call = result.message.parts.filterIsInstance<MessagePart.Tool.Call>().single()
		assertEquals("GET_SKILL", call.tool)
		assertEquals("abc", mapper.readTree(call.args).path("skillId").stringValue())
	}

	@Test
	fun `EOF without completion fails instead of accepting partial text`() = provider(listOf(chunk("{\"content\":\"partial\"}"))) { transport, _ ->
		val failure = assertFailsWith<AgentDecisionException> {
			runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList()) }
		}
		assertEquals("AGENT_INVALID_RESPONSE", failure.code)
	}

	@Test
	fun `HTTP 200 error frame fails with safe message`() = provider(listOf(
		chunk("{\"content\":\"partial\"}"),
		"""{"error":{"code":503,"message":"private provider details"},"choices":[{"index":0,"delta":{},"finish_reason":"error"}]}""",
	)) { transport, _ ->
		val failure = assertFailsWith<AgentDecisionException> {
			runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList()) }
		}
		assertEquals("PROVIDER_UNAVAILABLE", failure.code)
		assertFalse(failure.toString().contains("private"))
	}

	@Test
	fun `text observer receives only deltas before final response`() = provider(listOf(
		chunk("{\"content\":\"one \"}"), chunk("{\"content\":\"two\"}", "stop"), "[DONE]",
	)) { transport, _ ->
		val deltas = mutableListOf<String>()
		val response = runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList(), deltas::add) }
		assertEquals(listOf("one ", "two"), deltas)
		assertEquals("one two", response.message.textContent())
	}

	@Test
	fun `writer schema streams but still validates final JSON`() {
		val fragments = listOf("{\"answer\":\"", "작성 중", "\"}")
		provider(fragments.mapIndexed { index, text -> chunk(mapper.writeValueAsString(mapOf("content" to text)), if (index == fragments.lastIndex) "stop" else null) } + "[DONE]") { transport, request ->
			val observed = mutableListOf<String>()
			val result = transport.exchange("System", "User", """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}""", Output::class.java, 100, null, observed::add)
			assertEquals(fragments, observed)
			assertEquals("작성 중", result.value.answer)
			assertTrue(mapper.readTree(request()).path("stream").booleanValue())
			assertTrue(mapper.readTree(request()).path("response_format").path("json_schema").path("strict").booleanValue())
		}
	}

	@Test
	fun `observer failure escapes unchanged instead of blaming provider`() = provider(listOf(chunk("{\"content\":\"one\"}", "stop"), "[DONE]")) { transport, _ ->
		val lost = IllegalStateException("lease lost")
		val actual = assertFailsWith<IllegalStateException> {
			runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList()) { throw lost } }
		}
		assertSame(lost, actual)
	}

	@Test
	fun `fast provider burst loses no frames`() = provider(List(1_000) { chunk("{\"content\":\"x\"}") } + listOf(chunk("{}", "stop"), "[DONE]")) { transport, _ ->
		val observed = mutableListOf<String>()
		val result = runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList(), observed::add) }
		assertEquals("x".repeat(1_000), result.message.textContent())
		assertEquals(1_000, observed.size)
	}

	@Test
	fun `timeout closes a silent stream without waiting for provider EOF`() {
		val started = System.nanoTime()
		provider(listOf(chunk("{\"content\":\"late\"}", "stop"), "[DONE]"), delayMs = 1_000, timeoutMs = 200) { transport, _ ->
			val failure = assertFailsWith<AgentDecisionException> {
				runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList()) }
			}
			assertEquals("PROVIDER_UNAVAILABLE", failure.code)
			assertNotNull(failure.usage)
		}
		assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2_000)
	}

	@Test
	fun `oversized chat text is rejected before the observer sees it`() = provider(listOf(
		chunk(mapper.writeValueAsString(mapOf("content" to "x".repeat(40_001))), "stop"), "[DONE]",
	)) { transport, _ ->
		var observed = 0
		val failure = assertFailsWith<AgentDecisionException> {
			runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList()) { observed += it.length } }
		}
		assertEquals("AGENT_INVALID_RESPONSE", failure.code)
		assertEquals(0, observed)
	}

	@Test
	fun `empty text around reasoning adds no synthetic newlines to final answer`() = provider(listOf(
		chunk("{\"content\":\"\",\"reasoning\":\"private\"}"),
		chunk("{\"content\":\"\",\"reasoning\":\"thought\"}"),
		chunk("{\"content\":\"answer\"}", "stop"), "[DONE]",
	)) { transport, _ ->
		val deltas = mutableListOf<String>()
		val result = runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList(), deltas::add) }
		assertEquals("answer", deltas.joinToString(""))
		assertEquals("answer", result.message.textContent())
	}

	@Test
	fun `usage survives final usage then EOF and error and reaches native settlement hook`() {
		for (tail in listOf(emptyList(), listOf("""{"error":{"code":503,"message":"private"}}"""))) {
			provider(listOf(chunk("""{"content":"answer"}""", "stop", """, "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"cost":0.001}""")) + tail) { transport, _ ->
				var settled: ProviderUsage? = null
				var aborted = false
				val host = object : AgentRuntimeHost {
					override fun context() = AgentDecisionRequest(java.util.UUID.randomUUID(), "Hello", emptyList(), emptyList(), emptyList(), 1, 1, responseMode = AgentResponseMode.FLEXIBLE)
					override fun beforeModel() = Unit
					override fun afterModel(usage: ProviderUsage) { settled = usage }
					override fun modelFailed(failure: AgentDecisionException) { aborted = true }
					override fun execute(decision: AgentDecision) = error("No tools")
					override val finished = false
				}
				assertFailsWith<AgentDecisionException> { KoogAgentRuntime(transport, mapper).run(host) }
				assertFalse(aborted)
				assertEquals(10L, settled?.totalTokens)
				assertEquals("0.001", settled?.reportedCostUsd?.toPlainString())
			}
		}
	}

	@Test
	fun `tool names and IDs cannot bypass total stream bound`() = provider((0..2).map { index ->
		chunk(mapper.writeValueAsString(mapOf("tool_calls" to listOf(mapOf("index" to index, "id" to "call-$index", "type" to "function", "function" to mapOf("name" to "X".repeat(140_000), "arguments" to "{}"))))), "tool_calls")
	} + "[DONE]") { transport, _ ->
		assertFailsWith<AgentDecisionException> {
			runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList()) }
		}
	}

	@Test
	fun `external collector cancellation promptly closes a blocked body read`() {
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		val sent = java.util.concurrent.CountDownLatch(1)
		val disconnected = java.util.concurrent.CountDownLatch(1)
		server.executor = java.util.concurrent.Executors.newCachedThreadPool()
		server.createContext("/stream") { exchange ->
			exchange.responseHeaders.add("Content-Type", "text/event-stream")
			exchange.sendResponseHeaders(200, 0)
			try {
				exchange.responseBody.use { body ->
					body.write("data: first\n\n".toByteArray()); body.flush(); sent.countDown()
					Thread.sleep(300)
					while (true) { body.write(ByteArray(32_768) { 120 }); body.flush(); Thread.sleep(10) }
				}
			} catch (_: java.io.IOException) { disconnected.countDown() }
		}
		server.start()
		val url = "http://127.0.0.1:${server.address.port}"
		val client = StreamingHttpClient(JavaKoogHttpClient.Factory().create(clientName = "test", baseUrl = url), url, emptyMap(), Duration.ofSeconds(20))
		try {
			kotlinx.coroutines.runBlocking {
				val job = launch(kotlinx.coroutines.Dispatchers.Default) {
					client.sse("/stream", "{}", String::class, { true }, { it }, { it }, emptyMap(), emptyMap()).collect { }
				}
				assertTrue(sent.await(2, java.util.concurrent.TimeUnit.SECONDS))
				kotlinx.coroutines.delay(100)
				kotlinx.coroutines.withTimeout(1_000) { job.cancel(); job.join() }
			}
			assertTrue(disconnected.await(2, java.util.concurrent.TimeUnit.SECONDS), "Provider observes closed connection")
		} finally { client.close(); server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
	}

	@Test
	fun `writer failure preserves known usage instead of accepting incomplete output`() {
		for (tail in listOf(emptyList(), listOf("""{"error":{"code":503,"message":"private"}}"""))) {
			provider(listOf(chunk(mapper.writeValueAsString(mapOf("content" to mapper.writeValueAsString(Output("ok")))), "stop", """, "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"cost":0.001}""")) + tail) { transport, _ ->
				val failure = assertFailsWith<RuntimeException> {
					transport.exchange("System", "User", """{"type":"object"}""", Output::class.java, 100, null) { }
				}
				val usage = when (failure) {
					is MalformedModelOutputException -> failure.usage
					is TransientModelTransportException -> failure.usage
					else -> null
				}
				assertEquals(10L, usage?.totalTokens)
				assertEquals("0.001", usage?.reportedCostUsd?.toPlainString())
			}
		}
	}

	@Test
	fun `empty frames cannot accumulate without a frame bound`() = provider(List(4_100) { chunk("{}") } + listOf(chunk("{}", "stop"), "[DONE]")) { transport, _ ->
		assertFailsWith<AgentDecisionException> {
			runBlocking { transport.exchangeAgent(prompt("test") { user("Hello") }, transport.agentModel(), emptyList()) }
		}
	}

	data class Output(val answer: String)

	private fun chunk(delta: String, finish: String? = null, usage: String = "") =
		"""{"created":1,"id":"gen-test","model":"served-test","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]$usage}"""

	private fun provider(events: List<String>, delayMs: Long = 0, timeoutMs: Long = 2_000, check: (KoogModelTransport, () -> String) -> Unit) {
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		var request = ""
		server.createContext("/api/v1/chat/completions") { exchange ->
			request = exchange.requestBody.bufferedReader().readText()
			exchange.responseHeaders.add("Content-Type", "text/event-stream")
			exchange.sendResponseHeaders(200, 0)
			exchange.responseBody.use { body ->
				body.write(": heartbeat\r\n\r\n".toByteArray())
				for (event in events) { if (delayMs > 0) Thread.sleep(delayMs); body.write("data: $event\r\n\r\n".toByteArray()); body.flush() }
			}
		}
		server.start()
		val url = "http://127.0.0.1:${server.address.port}"
		val client = StreamingHttpClient(JavaKoogHttpClient.Factory().create(clientName = "test", baseUrl = url, requestTimeoutMillis = 2_000), url, emptyMap(), Duration.ofMillis(timeoutMs))
		try {
			check(KoogModelTransport(PlotAiProperties(enabled = true, model = PlotAiProperties.GPT_5_4_NANO_MODEL, routingProvider = "openai", timeout = Duration.ofMillis(timeoutMs)), mapper, client)) { request }
		} finally { client.close(); server.stop(0) }
	}
}
