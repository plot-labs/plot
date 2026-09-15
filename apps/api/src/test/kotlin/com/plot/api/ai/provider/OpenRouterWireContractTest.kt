package com.plot.api.ai.provider

import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.http.client.java.JavaKoogHttpClient
import com.plot.api.ai.prompt.ChangelogPrompt
import com.plot.api.config.PlotAiProperties
import com.plot.api.artifact.workflow.model.WriterOutput
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class OpenRouterWireContractTest {
    private val mapper = jacksonObjectMapper()
    private val properties = PlotAiProperties(enabled = true, model = PlotAiProperties.GPT_5_4_NANO_MODEL, routingProvider = "openai")

    @Test fun `Koog sends strict schema and pinned private route without tools`() {
        withServer { server, bodies, _ ->
            transport(server).useTransport { transport ->
                val result = transport.exchange(StructuredChatRequest(ModelRole.WRITER, ChangelogPrompt("System", "User")), WriterOutput::class.java)
                val body = mapper.readTree(bodies.single())
                assertEquals(properties.model, body["model"].stringValue())
                assertEquals(mapper.readTree(mapper.writeValueAsString(properties.openRouterProviderPolicy)), body["provider"])
                assertFalse(body.has("temperature"))
                assertFalse(body.has("tools") && body["tools"].size() > 0)
                assertEquals(ModelSchemas.WRITER.let(mapper::readTree), body["response_format"]["json_schema"]["schema"])
                assertTrue(body["response_format"]["json_schema"]["strict"].booleanValue())
                assertEquals(4000, (body.get("max_tokens") ?: body["max_completion_tokens"]).intValue())
                assertEquals("Wire contract passed.", result.value.sentences.single().body)
                assertEquals("gen-wire", result.responseId)
                assertEquals("openai/served-model", result.actualModel)
                assertEquals(18, result.totalTokens)
            }
        }
    }

    @Test fun `provider failures make one call and never retain private bodies`() {
        for (status in listOf(400, 401, 429, 503)) {
            withServer(status) { server, _, calls ->
                transport(server).useTransport { transport ->
                    val failure = assertFailsWith<RuntimeException> {
                        transport.exchange(StructuredChatRequest(ModelRole.WRITER, ChangelogPrompt("System", "User")), WriterOutput::class.java)
                    }
                    assertEquals(status >= 500 || status == 429, failure is TransientModelTransportException)
                    assertEquals(1, calls.get())
                    assertNull(failure.cause)
                    assertFalse(failure.message.orEmpty().contains("private-source"))
                }
            }
        }
    }

    @Test fun `truncated and malformed output fail safely`() {
        for (response in listOf(success("length"), success().replace("Wire contract passed.", "bad\\\"json"))) {
            withServer(response = response) { server, _, _ ->
                transport(server).useTransport { transport ->
                    assertFailsWith<MalformedModelOutputException> {
                        transport.exchange(StructuredChatRequest(ModelRole.WRITER, ChangelogPrompt("System", "User")), WriterOutput::class.java)
                    }
                }
            }
        }
    }

    @Test fun `unsafe configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> { properties.copy(allowFallbacks = true) }
        assertFailsWith<IllegalArgumentException> { properties.copy(routingProvider = null) }
        assertFailsWith<IllegalArgumentException> { properties.copy(baseUrl = "https://api.openai.com") }
        assertFailsWith<IllegalArgumentException> { properties.copy(contentLoggingEnabled = true) }
    }

    @Test fun `runtime sends native tools and accepts OpenRouter tool calls`() {
        val inputId = java.util.UUID.randomUUID()
        val response = """{"id":"gen-native","object":"chat.completion","created":1784160000,"model":"openai/served-model","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"CREATE_ARTIFACT","arguments":"{\"selectedInputIds\":[\"$inputId\"]}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}"""
        withServer(response = response) { server, bodies, calls ->
            transport(server).useTransport { transport ->
                var handedOff = false
                val host = object : AgentRuntimeHost {
                    override val finished get() = handedOff
                    override fun beforeModel() = Unit
                    override fun context() = AgentDecisionRequest(java.util.UUID.randomUUID(), "Create draft", emptyList(), emptyList(), emptyList(), 8, 8)
                    override fun execute(decision: AgentDecision): String {
                        assertEquals(listOf(inputId), decision.selectedInputIds)
                        handedOff = true
                        return "Created"
                    }
                }
                KoogAgentRuntime(transport, mapper).run(host)
                assertTrue(handedOff)
                assertEquals(1, calls.get())
                val body = mapper.readTree(bodies.single())
                assertEquals(6, body["tools"].size())
                assertFalse(body.has("response_format"))
                assertEquals(mapper.readTree(mapper.writeValueAsString(properties.openRouterProviderPolicy)), body["provider"])
            }
        }
    }

    private fun transport(server: HttpServer) = KoogModelTransport(properties, mapper, JavaKoogHttpClient.Factory().create(
        clientName = "test", baseUrl = "http://127.0.0.1:${server.address.port}"))

    private fun KoogModelTransport.useTransport(block: (KoogModelTransport) -> Unit) { try { block(this) } finally { close() } }

    private fun withServer(status: Int = 200, response: String = success(), block: (HttpServer, MutableList<String>, AtomicInteger) -> Unit) {
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            bodies.add(exchange.requestBody.readAllBytes().decodeToString())
            val bytes = (if (status == 200) response else """{"error":{"message":"private-source","code":$status}}""").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block(server, bodies, calls) } finally { server.stop(0) }
    }

    private fun success(finish: String = "stop") = """{"id":"gen-wire","object":"chat.completion","created":1784160000,"model":"openai/served-model","choices":[{"index":0,"message":{"role":"assistant","content":"{\"sentences\":[{\"body\":\"Wire contract passed.\",\"intent\":\"FACTUAL\",\"conflictEvidenceIds\":[]}],\"layout\":[]}"},"finish_reason":"$finish"}],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}"""
}
