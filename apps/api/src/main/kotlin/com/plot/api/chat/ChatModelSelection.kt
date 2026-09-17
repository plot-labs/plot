package com.plot.api.chat

import com.plot.api.common.ApiException
import com.plot.api.config.PlotAiProperties
import java.util.Locale
import org.springframework.http.HttpStatus

data class ChatModelSelection(
	val requestedModel: String,
	val model: String?,
	val routingProvider: String?,
	val reasoningEffort: String? = ChatReasoningEfforts.DEFAULT,
)

data class ChatModelCapability(
	val model: String,
	val reasoningEfforts: List<String>,
	val reasoningDefault: String?,
)

object ChatReasoningEfforts {
	const val DEFAULT = "medium"
	val SUPPORTED = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

	private data class Capability(
		val supported: List<String>,
		val default: String?,
	)

	/* OpenRouter model catalog reasoning.supported_efforts. Keep this beside
	 * admission validation so stale clients cannot send an effort the selected
	 * model cannot consume. */
	private val modelCapabilities = mapOf(
		PlotAiProperties.CLAUDE_OPUS_5_MODEL to Capability(listOf("low", "medium", "high", "xhigh", "max"), "high"),
		PlotAiProperties.CLAUDE_OPUS_4_8_MODEL to Capability(listOf("low", "medium", "high", "xhigh", "max"), "high"),
		PlotAiProperties.CLAUDE_SONNET_5_MODEL to Capability(listOf("low", "medium", "high", "xhigh", "max"), "high"),
		PlotAiProperties.CLAUDE_SONNET_4_6_MODEL to Capability(listOf("low", "medium", "high", "max"), "medium"),
		PlotAiProperties.CLAUDE_HAIKU_4_5_MODEL to Capability(emptyList(), null),
		PlotAiProperties.GPT_5_4_NANO_MODEL to Capability(listOf("none", "low", "medium", "high", "xhigh"), "medium"),
		PlotAiProperties.GPT_5_4_MODEL to Capability(listOf("none", "low", "medium", "high", "xhigh"), "medium"),
		PlotAiProperties.GPT_5_5_MODEL to Capability(listOf("none", "low", "medium", "high", "xhigh"), "medium"),
		PlotAiProperties.GPT_5_6_SOL_MODEL to Capability(listOf("none", "low", "medium", "high", "xhigh", "max"), "medium"),
		PlotAiProperties.GPT_5_6_LUNA_MODEL to Capability(listOf("none", "low", "medium", "high", "xhigh", "max"), "medium"),
		PlotAiProperties.GPT_5_6_LUNA_PRO_MODEL to Capability(listOf("none", "low", "medium", "high", "xhigh", "max"), "medium"),
		PlotAiProperties.GPT_4O_MINI_MODEL to Capability(emptyList(), null),
		PlotAiProperties.GEMINI_3_8_FLASH_MODEL to Capability(listOf("low", "medium", "high"), "medium"),
		PlotAiProperties.DEEPSEEK_V4_FLASH_MODEL to Capability(listOf("low", "high", "max"), "high"),
		PlotAiProperties.DEEPSEEK_V4_1_FLASH_MODEL to Capability(listOf("low", "high", "max"), "high"),
		PlotAiProperties.GROK_4_6_MODEL to Capability(listOf("low", "medium", "high", "xhigh"), "high"),
		PlotAiProperties.QWEN_3_8_MAX_MODEL to Capability(listOf("minimal", "low", "medium", "high", "xhigh"), "xhigh"),
	)

	fun resolve(requested: String, model: String? = null): String? {
		val normalized = requested.trim().lowercase(Locale.ROOT)
		if (normalized !in SUPPORTED) {
			throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REASONING_EFFORT", "Select a supported reasoning effort")
		}
		val capability = modelCapabilities[model] ?: return normalized
		if (capability.supported.isEmpty()) return null
		if (normalized !in capability.supported) {
			/* The old client default was medium. Preserve existing requests when
			 * a model's provider default is different, while rejecting every
			 * explicit unsupported level. */
			if (normalized == DEFAULT) return capability.default
			throw ApiException(
				HttpStatus.BAD_REQUEST,
				"INVALID_REASONING_EFFORT",
				"The selected model does not support reasoning effort '$normalized'",
			)
		}
		return normalized
	}

	fun capabilityFor(model: String?): ChatModelCapability {
		val capability = modelCapabilities[model]
		return ChatModelCapability(
			model = model ?: "auto",
			reasoningEfforts = capability?.supported ?: SUPPORTED,
			reasoningDefault = capability?.default ?: DEFAULT,
		)
	}
}

object ChatModels {
	const val AUTO = "auto"

	private val routingProviders = mapOf(
		PlotAiProperties.CLAUDE_OPUS_5_MODEL to "anthropic",
		PlotAiProperties.CLAUDE_OPUS_4_8_MODEL to "anthropic",
		PlotAiProperties.CLAUDE_SONNET_5_MODEL to "anthropic",
		PlotAiProperties.CLAUDE_SONNET_4_6_MODEL to "anthropic",
		PlotAiProperties.CLAUDE_HAIKU_4_5_MODEL to "anthropic",
		PlotAiProperties.GPT_5_4_MODEL to "openai",
		PlotAiProperties.GPT_5_5_MODEL to "openai",
		PlotAiProperties.GPT_5_6_SOL_MODEL to "openai",
		PlotAiProperties.GPT_5_6_LUNA_MODEL to "openai",
		PlotAiProperties.GEMINI_3_8_FLASH_MODEL to "google-ai-studio",
		PlotAiProperties.DEEPSEEK_V4_1_FLASH_MODEL to "deepinfra",
		PlotAiProperties.GROK_4_6_MODEL to "xai",
		PlotAiProperties.QWEN_3_8_MAX_MODEL to "alibaba",
	)

	fun capabilities(properties: PlotAiProperties): List<ChatModelCapability> =
		(listOf(AUTO) + routingProviders.keys.toList())
			.map { requestedModel ->
				val targetModel = if (requestedModel == AUTO) properties.model else requestedModel
				val capability = ChatReasoningEfforts.capabilityFor(targetModel)
				capability.copy(model = requestedModel)
			}

	fun resolve(
		requestedModel: String,
		properties: PlotAiProperties,
		requestedReasoningEffort: String = ChatReasoningEfforts.DEFAULT,
	): ChatModelSelection {
		val normalized = requestedModel.trim()
		val targetModel = if (normalized == AUTO) properties.model else normalized
		val reasoningEffort = ChatReasoningEfforts.resolve(requestedReasoningEffort, targetModel)
		if (normalized == AUTO) {
			return ChatModelSelection(AUTO, properties.model, properties.routingProvider, reasoningEffort)
		}
		val routingProvider = routingProviders[normalized]
			?: throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_MODEL", "Select an available Chat model")
		return ChatModelSelection(normalized, normalized, routingProvider, reasoningEffort)
	}
}
