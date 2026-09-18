package com.plot.api.billing

import com.plot.api.ai.provider.ProviderUsage
import com.plot.api.config.PlotAiProperties
import java.math.BigDecimal
import java.math.RoundingMode
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Autowired

enum class AiBillingBasis { REPORTED_COST }

data class AiCreditCharge(
	val providerCostUsd: BigDecimal,
	val credits: Long,
	val basis: AiBillingBasis,
	val policyVersion: String,
)

class AiUsageUnknownException(message: String) : RuntimeException(message) {
	val safeCode: String = "usage_unknown"
}

@Component
class AiCreditCalculator internal constructor(private val policy: AiCreditPolicy) {
	@Autowired
	constructor(properties: PlotAiProperties) : this(AiCreditPolicy()) {
		require(properties.creditPolicyVersion == policy.version) {
			"Configured AI credit policy does not match the deployed credit policy"
		}
	}

	fun calculate(usage: ProviderUsage): AiCreditCharge {
		usage.actualModel?.takeIf(String::isNotBlank)
			?: unknown("Provider usage did not identify the actual model")
		val input = nonNegative(usage.inputTokens, "input tokens")
		val output = nonNegative(usage.outputTokens, "output tokens")
		val total = nonNegative(usage.totalTokens, "total tokens")
		val cacheRead = nonNegative(usage.cacheReadTokens ?: 0, "cache read tokens")
		val cacheWrite = nonNegative(usage.cacheWriteTokens ?: 0, "cache write tokens")
		val reasoning = nonNegative(usage.reasoningTokens ?: 0, "reasoning tokens")
		if (total != input + output || cacheRead + cacheWrite > input || reasoning > output) {
			unknown("Provider usage token details are inconsistent")
		}

		val cost = usage.reportedCostUsd ?: unknown("Provider usage is missing actual cost")
		if (cost < BigDecimal.ZERO) unknown("Provider usage cost is invalid")
		val credits = cost.multiply(policy.debitMultiplier)
			.divide(policy.creditUnitUsd, 0, RoundingMode.CEILING)
			.longValueExact()
			.coerceAtLeast(1)
		return AiCreditCharge(cost.stripTrailingZeros(), credits, AiBillingBasis.REPORTED_COST, policy.version)
	}

	fun recurringBenefitCreditCap(planPriceUsd: BigDecimal): Long {
		require(planPriceUsd > BigDecimal.ZERO)
		val netRevenue = planPriceUsd.multiply(BigDecimal.ONE - policy.polarFeeRate) - policy.polarFixedFeeUsd
		val targetProfit = planPriceUsd.multiply(policy.targetAiGrossMarginRate)
		val spendableProviderCost = (netRevenue - targetProfit).coerceAtLeast(BigDecimal.ZERO)
		return policy.debitMultiplier.multiply(spendableProviderCost)
			.divide(BigDecimal.ONE + policy.providerFundingFeeRate, 12, RoundingMode.DOWN)
			.divide(policy.creditUnitUsd, 0, RoundingMode.FLOOR)
			.longValueExact()
	}

	val policyVersion: String get() = policy.version

	private fun nonNegative(value: Long?, label: String): Long {
		if (value == null || value < 0) unknown("Provider usage is missing valid $label")
		return value
	}

	private fun unknown(message: String): Nothing = throw AiUsageUnknownException(message)

}
