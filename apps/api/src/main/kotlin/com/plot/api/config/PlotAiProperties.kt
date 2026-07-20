package com.plot.api.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.ai")
data class PlotAiProperties(
	val enabled: Boolean = false,
	val provider: String = "openrouter",
	val model: String? = null,
	val baseUrl: String = OPENROUTER_BASE_URL,
	val routingProvider: String? = null,
	val allowFallbacks: Boolean = false,
	val requireParameters: Boolean = false,
	val zeroDataRetention: Boolean = false,
	val contentLoggingEnabled: Boolean = false,
	val timeout: Duration = Duration.ofSeconds(45),
	val writerTemperature: Double = 0.2,
	val reviewerTemperature: Double = 0.0,
	val maxOutputTokens: Int = 4_000,
	val transportRetries: Int = 1,
	val schemaRetries: Int = 1,
	val maxModelCalls: Int = 12,
	val maxTotalTokens: Int = 80_000,
	val maxRunDuration: Duration = Duration.ofMinutes(5),
	val claimTimeout: Duration = Duration.ofMinutes(10),
	val retryInitialDelay: Duration = Duration.ofMillis(250),
	val maxEvidenceCharacters: Int = 120_000,
) {
	val configured: Boolean
		get() = enabled && provider == OPENROUTER_GATEWAY && !model.isNullOrBlank()

	val supportsTemperature: Boolean
		get() = model == GPT_4O_MINI_MODEL

	val openRouterProviderPolicy: Map<String, Any>
		get() {
			val pinnedProvider = requireNotNull(routingProvider)
			return mapOf(
				"order" to listOf(pinnedProvider),
				"only" to listOf(pinnedProvider),
				"allow_fallbacks" to allowFallbacks,
				"require_parameters" to requireParameters,
				"data_collection" to "deny",
				"zdr" to zeroDataRetention,
			)
		}

	init {
		require(transportRetries in 0..3) { "plot.ai.transport-retries must be between 0 and 3" }
		require(schemaRetries in 0..2) { "plot.ai.schema-retries must be between 0 and 2" }
		require(maxOutputTokens > 0) { "plot.ai.max-output-tokens must be positive" }
		require(maxModelCalls > 0 && maxTotalTokens > 0) { "plot.ai run budgets must be positive" }
		require(maxEvidenceCharacters > 0) { "plot.ai.max-evidence-characters must be positive" }
		require(!retryInitialDelay.isNegative) { "plot.ai.retry-initial-delay must not be negative" }
		val requestEnvelope = timeout.multipliedBy((transportRetries + schemaRetries + 1).toLong())
		val backoffEnvelope = (0 until transportRetries).fold(Duration.ZERO) { total, attempt ->
			total.plus(retryInitialDelay.multipliedBy(1L shl attempt.coerceAtMost(8)))
		}
		require(claimTimeout > requestEnvelope.plus(backoffEnvelope)) {
			"plot.ai.claim-timeout must exceed the configured model retry and backoff envelope"
		}
		if (enabled && !model.isNullOrBlank()) {
			require(provider == OPENROUTER_GATEWAY) { "plot.ai.provider must be openrouter when generation is enabled" }
			require(baseUrl == OPENROUTER_BASE_URL) { "plot.ai.base-url must be the canonical OpenRouter API origin" }
			require(model in SUPPORTED_MODELS) { "plot.ai.model must use a supported pinned OpenRouter profile" }
			require(!routingProvider.isNullOrBlank() && ROUTING_PROVIDER_SLUG.matches(routingProvider)) {
				"plot.ai.routing-provider must pin exactly one OpenRouter provider slug"
			}
			require(!allowFallbacks) { "plot.ai.allow-fallbacks must remain false" }
			require(!contentLoggingEnabled) { "plot.ai.content-logging-enabled must remain false" }
		}
	}

	companion object {
		const val OPENROUTER_GATEWAY = "openrouter"
		const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
		const val GPT_5_4_NANO_MODEL = "openai/gpt-5.4-nano"
		const val GPT_4O_MINI_MODEL = "openai/gpt-4o-mini-2024-07-18"
		val SUPPORTED_MODELS = setOf(GPT_5_4_NANO_MODEL, GPT_4O_MINI_MODEL)
		private val ROUTING_PROVIDER_SLUG = Regex("[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*")
	}
}
