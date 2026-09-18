package com.plot.api.billing

import com.plot.api.config.PlotAiProperties
import java.math.BigDecimal

data class AiModelPrice(
	val inputPerMillionUsd: BigDecimal,
	val outputPerMillionUsd: BigDecimal,
	val cacheReadPerMillionUsd: BigDecimal,
	val cacheWritePerMillionUsd: BigDecimal,
	val reasoningPerMillionUsd: BigDecimal = outputPerMillionUsd,
) {
	init {
		listOf(
			inputPerMillionUsd,
			outputPerMillionUsd,
			cacheReadPerMillionUsd,
			cacheWritePerMillionUsd,
			reasoningPerMillionUsd,
		).forEach { require(it >= BigDecimal.ZERO) { "AI model prices must not be negative" } }
	}
}

data class AiPricingPolicy(
	val version: String,
	val models: Map<String, AiModelPrice>,
	val creditUnitUsd: BigDecimal = BigDecimal("0.001"),
	val debitMultiplier: BigDecimal = BigDecimal("1.5"),
	val polarFeeRate: BigDecimal = BigDecimal("0.05"),
	val polarFixedFeeUsd: BigDecimal = BigDecimal("0.50"),
	val providerFundingFeeRate: BigDecimal = BigDecimal("0.055"),
	val targetAiGrossMarginRate: BigDecimal = BigDecimal("0.70"),
) {
	init {
		require(version.isNotBlank())
		require(models.isNotEmpty())
		require(creditUnitUsd.compareTo(BigDecimal("0.001")) == 0) { "AI credit unit is a fixed product constant" }
		require(debitMultiplier.compareTo(BigDecimal("1.5")) == 0) { "AI debit multiplier is a fixed product constant" }
	}

	companion object {
		fun current() = AiPricingPolicy(
			version = "2026-09-18-v1",
			models = mapOf(
				PlotAiProperties.GPT_5_4_NANO_MODEL to price("0.10", "0.40", "0.05", "0"),
				PlotAiProperties.GPT_5_6_LUNA_PRO_MODEL to price("2.00", "10.00", "0.20", "2.50"),
				PlotAiProperties.GPT_5_6_LUNA_MODEL to price("1.00", "5.00", "0.10", "1.25"),
				PlotAiProperties.GPT_5_6_SOL_MODEL to price("0.40", "1.60", "0.04", "0.50"),
				PlotAiProperties.GPT_4O_MINI_MODEL to price("0.15", "0.60", "0.075", "0"),
				PlotAiProperties.DEEPSEEK_V4_FLASH_MODEL to price("0.27", "1.10", "0.07", "0"),
				PlotAiProperties.DEEPSEEK_V4_1_FLASH_MODEL to price("0.27", "1.10", "0.07", "0"),
				PlotAiProperties.GEMINI_3_8_FLASH_MODEL to price("0.30", "2.50", "0.03", "0"),
				PlotAiProperties.GROK_4_6_MODEL to price("3.00", "15.00", "0.75", "3.75"),
				PlotAiProperties.QWEN_3_8_MAX_MODEL to price("1.20", "6.00", "0.12", "1.50"),
				PlotAiProperties.CLAUDE_OPUS_5_MODEL to price("5.00", "25.00", "0.50", "6.25"),
				PlotAiProperties.CLAUDE_OPUS_4_8_MODEL to price("5.00", "25.00", "0.50", "6.25"),
				PlotAiProperties.CLAUDE_SONNET_5_MODEL to price("2.00", "10.00", "0.20", "2.50"),
				PlotAiProperties.CLAUDE_SONNET_4_6_MODEL to price("3.00", "15.00", "0.30", "3.75"),
				PlotAiProperties.CLAUDE_HAIKU_4_5_MODEL to price("0.80", "4.00", "0.08", "1.00"),
				PlotAiProperties.GPT_5_4_MODEL to price("2.50", "15.00", "0.25", "0"),
				PlotAiProperties.GPT_5_5_MODEL to price("2.50", "15.00", "0.25", "0"),
			),
		).also { policy ->
			require(policy.models.keys == PlotAiProperties.SUPPORTED_MODELS) {
				"Every supported AI model must have an explicit price in ${policy.version}"
			}
		}

		private fun price(input: String, output: String, cacheRead: String, cacheWrite: String) = AiModelPrice(
			BigDecimal(input),
			BigDecimal(output),
			BigDecimal(cacheRead),
			BigDecimal(cacheWrite),
		)
	}
}
