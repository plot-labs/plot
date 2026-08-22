package com.plot.api.ai.provider

import com.openai.errors.OpenAIRetryableException
import com.openai.errors.OpenAIServiceException
import com.plot.api.config.PlotAiProperties
import java.util.UUID
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
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

private class SpringAiAgentDecisionGateway(
	builder: ChatClient.Builder,
	private val properties: PlotAiProperties,
	private val objectMapper: ObjectMapper,
) : AgentDecisionGateway {
	private val client = builder.clone().build()
	private val options = OpenAiChatOptions.builder()
		.baseUrl(properties.baseUrl)
		.model(requireNotNull(properties.model))
		.maxCompletionTokens(minOf(properties.maxOutputTokens, 2_000))
		.timeout(properties.timeout)
		.maxRetries(0)
		.customHeaders(mapOf(
			"X-OpenRouter-Metadata" to "enabled",
			"X-OpenRouter-Title" to "Plot",
		))
		.extraBody(mapOf("provider" to properties.openRouterProviderPolicy))
		.outputSchema(DECISION_SCHEMA)
		.build()

	override fun decide(request: AgentDecisionRequest): AgentDecision = try {
		client.prompt()
			.system(SYSTEM_PROMPT)
			.user(objectMapper.writeValueAsString(request))
			.options(options.mutate())
			.call()
			.responseEntity(AgentDecision::class.java)
			.entity
			?: throw AgentDecisionException("MALFORMED_OUTPUT", false, "The agent returned no decision")
	} catch (failure: AgentDecisionException) {
		throw failure
	} catch (failure: OpenAIRetryableException) {
		throw AgentDecisionException("PROVIDER_UNAVAILABLE", true, "The agent provider is temporarily unavailable", failure)
	} catch (failure: OpenAIServiceException) {
		throw AgentDecisionException("PROVIDER_REJECTED", false, "The agent provider rejected the request", failure)
	} catch (failure: RuntimeException) {
		throw AgentDecisionException("MALFORMED_OUTPUT", false, "The agent returned an invalid decision", failure)
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
		builderProvider: ObjectProvider<ChatClient.Builder>,
		properties: PlotAiProperties,
		objectMapper: ObjectMapper,
	): AgentDecisionGateway {
		val builder = if (properties.configured) builderProvider.ifAvailable else null
		return if (properties.configured && builder != null) {
			SpringAiAgentDecisionGateway(builder, properties, objectMapper)
		} else {
			DisabledAgentDecisionGateway()
		}
	}
}
