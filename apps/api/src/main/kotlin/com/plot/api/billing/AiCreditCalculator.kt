package com.plot.api.billing

import com.plot.api.ai.provider.ProviderUsage
import com.plot.api.config.PlotAiProperties
import java.math.BigDecimal
import java.math.RoundingMode
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Autowired

enum class AiBillingBasis { REPORTED_COST, TOKENS }

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
class AiCreditCalculator internal constructor(private val policy: AiPricingPolicy) {
	@Autowired
	constructor(properties: PlotAiProperties) : this(AiPricingPolicy.current()) {
		require(properties.creditPolicyVersion == policy.version) {
			"Configured AI credit policy does not match the deployed price catalog"
		}
	}

	fun calculate(usage: ProviderUsage): AiCreditCharge {
		val actualModel = usage.actualModel?.takeIf(String::isNotBlank)
			?: unknown("Provider usage did not identify the actual model")
		val price = policy.models[actualModel] ?: unknown("Provider usage identified an unpriced model")
		val input = nonNegative(usage.inputTokens, "input tokens")
		val output = nonNegative(usage.outputTokens, "output tokens")
		val total = nonNegative(usage.totalTokens, "total tokens")
		val cacheRead = nonNegative(usage.cacheReadTokens ?: 0, "cache read tokens")
		val cacheWrite = nonNegative(usage.cacheWriteTokens ?: 0, "cache write tokens")
		val reasoning = nonNegative(usage.reasoningTokens ?: 0, "reasoning tokens")
		if (total != input + output || cacheRead + cacheWrite > input || reasoning > output) {
			unknown("Provider usage token details are inconsistent")
		}

		val reported = usage.reportedCostUsd
		if (reported != null && reported < BigDecimal.ZERO) unknown("Provider usage cost is invalid")
		val (cost, basis) = if (reported != null && reported > BigDecimal.ZERO) {
			reported to AiBillingBasis.REPORTED_COST
		} else {
			val uncachedInput = input - cacheRead - cacheWrite
			val ordinaryOutput = output - reasoning
			val tokenCost = tokenCost(uncachedInput, price.inputPerMillionUsd) +
				tokenCost(cacheRead, price.cacheReadPerMillionUsd) +
				tokenCost(cacheWrite, price.cacheWritePerMillionUsd) +
				tokenCost(ordinaryOutput, price.outputPerMillionUsd) +
				tokenCost(reasoning, price.reasoningPerMillionUsd)
			if (tokenCost <= BigDecimal.ZERO) unknown("Provider usage has no billable cost")
			tokenCost to AiBillingBasis.TOKENS
		}
		val credits = cost.multiply(policy.debitMultiplier)
			.divide(policy.creditUnitUsd, 0, RoundingMode.CEILING)
			.longValueExact()
			.coerceAtLeast(1)
		return AiCreditCharge(cost.stripTrailingZeros(), credits, basis, policy.version)
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

	private fun tokenCost(tokens: Long, perMillionUsd: BigDecimal): BigDecimal =
		BigDecimal.valueOf(tokens).multiply(perMillionUsd).divide(MILLION)

	private fun nonNegative(value: Long?, label: String): Long {
		if (value == null || value < 0) unknown("Provider usage is missing valid $label")
		return value
	}

	private fun unknown(message: String): Nothing = throw AiUsageUnknownException(message)

	private companion object {
		val MILLION = BigDecimal("1000000")
	}
}
