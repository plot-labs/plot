package com.plot.api.chat

import com.plot.api.common.ApiException
import com.plot.api.config.PlotAiProperties
import org.springframework.http.HttpStatus

data class ChatModelSelection(
	val requestedModel: String,
	val model: String?,
	val routingProvider: String?,
)

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

	fun resolve(requestedModel: String, properties: PlotAiProperties): ChatModelSelection {
		val normalized = requestedModel.trim()
		if (normalized == AUTO) {
			return ChatModelSelection(AUTO, properties.model, properties.routingProvider)
		}
		val routingProvider = routingProviders[normalized]
			?: throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_MODEL", "Select an available Chat model")
		return ChatModelSelection(normalized, normalized, routingProvider)
	}
}
