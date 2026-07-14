package com.plot.api.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.ai")
data class PlotAiProperties(
	val enabled: Boolean = false,
	val provider: String = "openai",
	val model: String? = null,
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
		get() = enabled && provider == "openai" && !model.isNullOrBlank()

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
	}
}
