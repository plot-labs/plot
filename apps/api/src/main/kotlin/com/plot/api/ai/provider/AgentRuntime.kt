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
	LIST_AVAILABLE_SKILLS,
	GET_SKILL,
}

data class AgentDecision(
	val action: AgentDecisionAction,
	val sourceScopeId: UUID? = null,
	val query: String? = null,
	val skillId: UUID? = null,
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
	val selectedSkillIds: List<UUID> = emptyList(),
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

/** Plot owns durable state and authorization; the runtime owns the model/tool loop. */
interface AgentRuntime {
	fun run(host: AgentRuntimeHost)
}

interface AgentRuntimeHost {
	fun context(): AgentDecisionRequest
	fun beforeModel()
	fun execute(decision: AgentDecision): String
	val finished: Boolean
	val modelTimeoutMillis: Long get() = 45_000
}

class AgentDecisionException(
	val code: String,
	val recoverable: Boolean,
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

@Configuration(proxyBeanMethods = false)
class AgentRuntimeConfiguration {
	@Bean
	fun agentRuntime(transport: KoogModelTransport, properties: PlotAiProperties, objectMapper: ObjectMapper): AgentRuntime =
		if (properties.configured) KoogAgentRuntime(transport, objectMapper)
		else object : AgentRuntime {
			override fun run(host: AgentRuntimeHost) {
				throw AgentDecisionException("MODEL_NOT_CONFIGURED", false, "The agent model is not configured")
			}
		}
}
