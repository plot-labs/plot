package com.plot.api.ai.provider

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class KoogAgentRuntimeTest {
	private val skillId = UUID.randomUUID()
	private val inputId = UUID.randomUUID()
	private val mapper = jacksonObjectMapper()

	@Test
	fun `native Koog loop discovers skills loads content then hands off without another model call`() {
		val prompts = mutableListOf<Prompt>()
		val host = Host()
		val calls = ArrayDeque(listOf(
			call("LIST_AVAILABLE_SKILLS"),
			call("GET_SKILL", """{"skillId":"$skillId"}"""),
			call("CREATE_ARTIFACT", """{"selectedInputIds":["$inputId"]}"""),
		))
		runtime { prompt -> prompts += prompt; calls.removeFirst() }.run(host)
		assertTrue(host.finished)
		assertEquals(3, host.modelCalls)
		assertEquals(listOf(AgentDecisionAction.LIST_AVAILABLE_SKILLS, AgentDecisionAction.GET_SKILL, AgentDecisionAction.CREATE_ARTIFACT), host.decisions.map { it.action })
		assertFalse(prompts[0].toString().contains("Private writing instructions"))
		assertFalse(prompts[1].toString().contains("Private writing instructions"))
		assertTrue(prompts[2].toString().contains("Private writing instructions"))
	}

	@Test
	fun `selected skill must be loaded before artifact handoff`() {
		val host = Host()
		val calls = ArrayDeque(listOf(call("CREATE_ARTIFACT"), call("GET_SKILL", """{"skillId":"$skillId"}"""), call("CREATE_ARTIFACT")))
		runtime { calls.removeFirst() }.run(host)
		assertEquals(3, host.modelCalls)
		assertEquals(listOf(AgentDecisionAction.GET_SKILL, AgentDecisionAction.CREATE_ARTIFACT), host.decisions.map { it.action })
	}

	@Test
	fun `host access failure aborts remaining tool calls and is not converted to model feedback`() {
		val denied = IllegalStateException("Access revoked")
		val host = Host(failure = denied)
		val response = Message.Assistant(parts = listOf(
			MessagePart.Tool.Call("one", "LIST_AVAILABLE_SKILLS", Json.parseToJsonElement("{}").jsonObject),
			MessagePart.Tool.Call("two", "LIST_ALLOWED_SOURCES", Json.parseToJsonElement("{}").jsonObject),
		), metaInfo = ResponseMetaInfo.Empty)
		assertSame(denied, assertFailsWith<IllegalStateException> { runtime { response }.run(host) })
		assertEquals(1, host.decisions.size)
		assertEquals(1, host.modelCalls)
	}

	@Test
	fun `model budget failure escapes the runtime`() {
		val host = Host(modelLimit = 1)
		assertFailsWith<IllegalStateException> { runtime { call("LIST_AVAILABLE_SKILLS") }.run(host) }
		assertEquals(1, host.modelCalls)
		assertEquals(1, host.decisions.size)
	}

	@Test
	fun `plain text cannot silently succeed without artifact`() {
		val failure = assertFailsWith<AgentDecisionException> {
			runtime { Message.Assistant("Done", ResponseMetaInfo.Empty) }.run(Host())
		}
		assertEquals("AGENT_NO_ARTIFACT", failure.code)
	}

	@Test
	fun `restored skill result permits handoff and restores instructions in context`() {
		val host = Host(restored = true)
		runtime { prompt ->
			assertTrue(prompt.toString().contains("Private writing instructions"))
			call("CREATE_ARTIFACT")
		}.run(host)
		assertEquals(1, host.modelCalls)
		assertTrue(host.finished)
	}

	private fun runtime(next: (Prompt) -> Message.Assistant) = KoogAgentRuntime(
		LLModel(LLMProvider.OpenRouter, "test", listOf(LLMCapability.Completion, LLMCapability.Tools)), LLMParams(), mapper,
	) { prompt, _, tools ->
		assertEquals(AgentDecisionAction.entries.map { it.name }.toSet(), tools.map { it.name }.toSet())
		next(prompt)
	}

	private fun call(name: String, args: String = "{}") = Message.Assistant(
		parts = listOf(MessagePart.Tool.Call(UUID.randomUUID().toString(), name, Json.parseToJsonElement(args).jsonObject)),
		metaInfo = ResponseMetaInfo.Empty,
	)

	private inner class Host(val failure: Exception? = null, val modelLimit: Int = 8, val restored: Boolean = false) : AgentRuntimeHost {
		val decisions = mutableListOf<AgentDecision>()
		var modelCalls = 0
		override var finished = false
		override fun context() = AgentDecisionRequest(UUID.randomUUID(), "Write a draft", emptyList(), emptyList(),
			if (restored) listOf(AgentStepView(0, "GET_SKILL", skill())) else emptyList(), 8, 8, listOf(skillId))
		override fun beforeModel() { check(modelCalls < modelLimit); modelCalls++ }
		override fun execute(decision: AgentDecision): String {
			decisions += decision
			failure?.let { throw it }
			return when (decision.action) {
				AgentDecisionAction.GET_SKILL -> skill()
				AgentDecisionAction.CREATE_ARTIFACT -> { finished = true; "Artifact started" }
				else -> """[{"id":"$skillId","name":"benefits","description":"Customer benefits"}]"""
			}
		}
		private fun skill() = """{"id":"$skillId","content":"Private writing instructions"}"""
	}
}
