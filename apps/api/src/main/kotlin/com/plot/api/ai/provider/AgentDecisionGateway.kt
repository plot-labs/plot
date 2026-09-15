package com.plot.api.ai.provider

import com.plot.api.config.PlotAiProperties
import java.util.UUID
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

enum class AgentDecisionAction {
	LIST_ALLOWED_SOURCES,
	SEARCH_WRITING_BLOCKS,
	READ_WRITING_BLOCKS,
	CREATE_ARTIFACT,
}

data class AgentDecision(
	val action: AgentDecisionAction,
	val sourceScopeId: UUID? = null,
	val query: String? = null,
	val writingBlockIds: List<UUID> = emptyList(),
	val selectedInputIds: List<UUID> = emptyList(),
)

data class AgentDecisionRequest(
	val agentRunId: UUID,
	val instruction: String,
	val sources: List<AgentSourceView>,
	val inputs: List<AgentInputView>,
	val completedSteps: List<AgentStepView>,
	val remainingModelCalls: Int,
	val remainingToolCalls: Int,
)

data class AgentSourceView(
	val id: UUID,
	val label: String,
	val role: String,
)

data class AgentInputView(
	val id: UUID,
	val sourceScopeId: UUID,
	val title: String?,
	val excerpt: String,
)

data class AgentStepView(
	val sequence: Int,
	val toolName: String?,
	val result: String?,
)

interface AgentDecisionGateway {
	fun decide(request: AgentDecisionRequest): AgentDecision
}

class AgentDecisionException(
	val code: String,
	val recoverable: Boolean,
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

private class DisabledAgentDecisionGateway : AgentDecisionGateway {
	override fun decide(request: AgentDecisionRequest): AgentDecision = throw AgentDecisionException(
		code = "MODEL_NOT_CONFIGURED",
		recoverable = false,
		message = "The agent model is not configured",
	)
}

internal class KoogAgentDecisionGateway(
	private val transport: KoogModelTransport,
	private val objectMapper: ObjectMapper,
) : AgentDecisionGateway {
	override fun decide(request: AgentDecisionRequest): AgentDecision = try {
		transport.exchange(SYSTEM_PROMPT, objectMapper.writeValueAsString(request), DECISION_SCHEMA,
			AgentDecision::class.java, 2_000, null).value
	} catch (failure: TransientModelTransportException) {
		throw AgentDecisionException("PROVIDER_UNAVAILABLE", true, "The agent provider is temporarily unavailable")
	} catch (failure: NonTransientModelTransportException) {
		throw AgentDecisionException("PROVIDER_REJECTED", false, "The agent provider rejected the request")
	} catch (failure: MalformedModelOutputException) {
		throw AgentDecisionException("MALFORMED_OUTPUT", false, "The agent returned an invalid decision")
	}

	private companion object {
		val SYSTEM_PROMPT = """
			You are Plot's read-only source research agent. Choose exactly one next action.
			Inputs already collected for this run carry an `id` (input ID); use those IDs only in
			`selectedInputIds` when creating an artifact. To read a source item in full, call
			SEARCH_WRITING_BLOCKS first and pass a `writingBlockId` from its matches to
			READ_WRITING_BLOCKS. Never use an input ID as a `writingBlockId`.
			Use only the source and input IDs supplied by the server. Read more context when needed,
			then choose CREATE_ARTIFACT with the immutable input IDs that should support the draft.
			If a previous step failed, read its error and adjust the next decision instead of repeating it.
			Never request a write or external action. Return structured fields only and never reveal hidden reasoning.
		""".trimIndent()

		val DECISION_SCHEMA = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,"required":["action","sourceScopeId","query","writingBlockIds","selectedInputIds"],"properties":{"action":{"type":"string","enum":["LIST_ALLOWED_SOURCES","SEARCH_WRITING_BLOCKS","READ_WRITING_BLOCKS","CREATE_ARTIFACT"]},"sourceScopeId":{"type":["string","null"],"format":"uuid"},"query":{"type":["string","null"]},"writingBlockIds":{"type":"array","maxItems":1,"items":{"type":"string","format":"uuid"}},"selectedInputIds":{"type":"array","items":{"type":"string","format":"uuid"}}}}"""
	}
}

@Configuration(proxyBeanMethods = false)
class AgentDecisionGatewayConfiguration {
	@Bean
	fun agentDecisionGateway(
		transport: KoogModelTransport,
		properties: PlotAiProperties,
		objectMapper: ObjectMapper,
	): AgentDecisionGateway = if (properties.configured) KoogAgentDecisionGateway(transport, objectMapper)
		else DisabledAgentDecisionGateway()
}
