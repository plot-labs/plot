package com.plot.api.ai.provider

import ai.koog.prompt.dsl.prompt
import com.plot.api.config.PlotAiProperties
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.core.env.StandardEnvironment
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import tools.jackson.module.kotlin.jacksonObjectMapper

@Tag("live-eval")
@EnabledIfEnvironmentVariable(named = "PLOT_EVAL_LIVE", matches = "true")
class KoogModelStreamingLiveTest {
	@Test
	fun `configured provider streams text and schema with accountable usage`() {
		val environment = StandardEnvironment()
		val properties = PlotAiProperties(
			enabled = true,
			model = requireNotNull(environment.getProperty("plot.ai.model")),
			routingProvider = requireNotNull(environment.getProperty("plot.ai.routing-provider")),
			maxOutputTokens = 512,
		)
		val transport = KoogModelTransport(properties, jacksonObjectMapper(), environment)
		try {
			val deltas = mutableListOf<String>()
			val response = runBlocking {
				transport.exchangeAgent(prompt("stream-check", transport.agentParams()) {
					user("Write a numbered list of five short greetings in Korean. Do not use tools.")
				}, transport.agentModel(), emptyList(), deltas::add)
			}
			assertTrue(deltas.size > 1)
			assertEquals(deltas.joinToString(""), response.message.textContent())
			assertNotNull(response.usage.responseId)
			assertNotNull(response.usage.actualModel)
			assertTrue((response.usage.totalTokens ?: 0) > 0)
			assertNotNull(response.usage.reportedCostUsd)
			val jsonDeltas = mutableListOf<String>()
			val output = transport.exchange("Return the requested JSON.", "Answer with a brief Korean greeting.",
				"""{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}""",
				KoogModelTransportStreamingTest.Output::class.java, 512, null, jsonDeltas::add)
			assertTrue(jsonDeltas.size > 1)
			assertTrue(output.value.answer.isNotBlank())
			assertTrue((output.totalTokens ?: 0) > 0)
			assertNotNull(output.reportedCostUsd)
			println("LIVE_STREAM textChunks=${deltas.size} schemaChunks=${jsonDeltas.size} textTokens=${response.usage.totalTokens} schemaTokens=${output.totalTokens}")
		} finally { transport.close() }
	}
}
