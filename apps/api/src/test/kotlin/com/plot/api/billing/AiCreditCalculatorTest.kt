package com.plot.api.billing

import com.plot.api.ai.provider.ProviderUsage
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class AiCreditCalculatorTest {
	private val calculator = AiCreditCalculator(
		AiPricingPolicy(
			version = "test-v1",
			models = mapOf(
				"model/cheap" to AiModelPrice(
					inputPerMillionUsd = BigDecimal("1"),
					outputPerMillionUsd = BigDecimal("2"),
					cacheReadPerMillionUsd = BigDecimal("0.1"),
					cacheWritePerMillionUsd = BigDecimal("1.25"),
					reasoningPerMillionUsd = BigDecimal("2"),
				),
				"model/expensive" to AiModelPrice(
					inputPerMillionUsd = BigDecimal("5"),
					outputPerMillionUsd = BigDecimal("25"),
					cacheReadPerMillionUsd = BigDecimal("0.5"),
					cacheWritePerMillionUsd = BigDecimal("6.25"),
					reasoningPerMillionUsd = BigDecimal("25"),
				),
			),
		),
	)

	@Test
	fun reportedProviderCostWinsAnd00368CostsSixCredits() {
		val charge = calculator.calculate(usage(reportedCostUsd = BigDecimal("0.00368")))

		assertEquals(BigDecimal("0.00368"), charge.providerCostUsd)
		assertEquals(6, charge.credits)
		assertEquals(AiBillingBasis.REPORTED_COST, charge.basis)
		assertEquals("test-v1", charge.policyVersion)
	}

	@Test
	fun sameTokensCostDifferentlyByActualModel() {
		val cheap = calculator.calculate(usage(actualModel = "model/cheap", input = 1_000, output = 1_000))
		val expensive = calculator.calculate(usage(actualModel = "model/expensive", input = 1_000, output = 1_000))

		assertEquals(5, cheap.credits)
		assertEquals(45, expensive.credits)
	}

	@Test
	fun cacheAndReasoningArePricedWithoutDoubleCountingTotals() {
		val charge = calculator.calculate(
			usage(input = 1_000, output = 1_000, cacheRead = 400, cacheWrite = 100, reasoning = 300),
		)

		assertEquals(BigDecimal("0.002665"), charge.providerCostUsd)
		assertEquals(4, charge.credits)
	}

	@Test
	fun positiveUsageAlwaysCostsAtLeastOneCredit() {
		val charge = calculator.calculate(usage(input = 1, output = 0))

		assertEquals(1, charge.credits)
	}

	@Test
	fun recurringBenefitCapsMatchTheFrozenCommercialBaseline() {
		assertEquals(6_398, calculator.recurringBenefitCreditCap(BigDecimal("20")))
		assertEquals(9_952, calculator.recurringBenefitCreditCap(BigDecimal("30")))
		assertEquals(17_061, calculator.recurringBenefitCreditCap(BigDecimal("50")))
		assertEquals(34_834, calculator.recurringBenefitCreditCap(BigDecimal("100")))
	}

	@Test
	fun failsClosedForUnknownModelOrIncompleteUsage() {
		assertEquals("usage_unknown", assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(actualModel = "model/unknown"))
		}.safeCode)
		assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(total = null))
		}
		assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(input = -1))
		}
		assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(input = 5, cacheRead = 6))
		}
	}

	@Test
	fun zeroReportedCostFallsBackToTokens() {
		val charge = calculator.calculate(usage(reportedCostUsd = BigDecimal.ZERO))

		assertEquals(AiBillingBasis.TOKENS, charge.basis)
	}

	private fun usage(
		actualModel: String = "model/cheap",
		input: Long = 1_000,
		output: Long = 1_000,
		cacheRead: Long = 0,
		cacheWrite: Long = 0,
		reasoning: Long = 0,
		total: Long? = input + output,
		reportedCostUsd: BigDecimal? = null,
	) = ProviderUsage(
		provider = "openrouter",
		requestedModel = "model/cheap",
		actualModel = actualModel,
		responseId = "response-1",
		inputTokens = input,
		outputTokens = output,
		cacheReadTokens = cacheRead,
		cacheWriteTokens = cacheWrite,
		reasoningTokens = reasoning,
		totalTokens = total,
		reportedCostUsd = reportedCostUsd,
	)
}
