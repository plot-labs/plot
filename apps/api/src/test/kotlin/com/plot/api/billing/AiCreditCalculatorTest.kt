package com.plot.api.billing

import com.plot.api.ai.provider.ProviderUsage
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class AiCreditCalculatorTest {
	private val calculator = AiCreditCalculator(AiCreditPolicy(version = "test-openrouter-cost"))

	@Test
	fun reportedProviderCostWinsAnd00368CostsSixCredits() {
		val charge = calculator.calculate(usage(reportedCostUsd = BigDecimal("0.00368")))

		assertEquals(BigDecimal("0.00368"), charge.providerCostUsd)
		assertEquals(6, charge.credits)
		assertEquals(AiBillingBasis.REPORTED_COST, charge.basis)
		assertEquals("test-openrouter-cost", charge.policyVersion)
	}

	@Test
	fun providerCostWorksForAnyReportedActualModelWithoutALocalPriceCatalog() {
		val first = calculator.calculate(usage(actualModel = "provider/model-a", reportedCostUsd = BigDecimal("0.001")))
		val second = calculator.calculate(usage(actualModel = "provider/model-b", reportedCostUsd = BigDecimal("0.010")))

		assertEquals(2, first.credits)
		assertEquals(15, second.credits)
	}

	@Test
	fun cacheAndReasoningDetailsAreValidatedButOpenRouterCostIsTheBillingAuthority() {
		val charge = calculator.calculate(
			usage(input = 1_000, output = 1_000, cacheRead = 400, cacheWrite = 100, reasoning = 300,
				reportedCostUsd = BigDecimal("0.002665")),
		)

		assertEquals(BigDecimal("0.002665"), charge.providerCostUsd)
		assertEquals(4, charge.credits)
	}

	@Test
	fun zeroCostOrSubUnitCallsStillCostAtLeastOneCredit() {
		val free = calculator.calculate(usage(input = 0, output = 0, reportedCostUsd = BigDecimal.ZERO))
		val tiny = calculator.calculate(usage(input = 1, output = 0, reportedCostUsd = BigDecimal("0.000001")))

		assertEquals(1, free.credits)
		assertEquals(1, tiny.credits)
	}

	@Test
	fun recurringBenefitCapsMatchTheFrozenCommercialBaseline() {
		assertEquals(6_398, calculator.recurringBenefitCreditCap(BigDecimal("20")))
		assertEquals(9_952, calculator.recurringBenefitCreditCap(BigDecimal("30")))
		assertEquals(17_061, calculator.recurringBenefitCreditCap(BigDecimal("50")))
		assertEquals(34_834, calculator.recurringBenefitCreditCap(BigDecimal("100")))
	}

	@Test
	fun failsClosedForMissingModelCostOrIncompleteUsage() {
		assertEquals("usage_unknown", assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(actualModel = ""))
		}.safeCode)
		assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(reportedCostUsd = null))
		}
		assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(total = null))
		}
		assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(input = -1))
		}
		assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(input = 5, cacheRead = 6))
		}
		assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(input = Long.MAX_VALUE, output = 1, total = Long.MAX_VALUE))
		}
		assertFailsWith<AiUsageUnknownException> {
			calculator.calculate(usage(reportedCostUsd = BigDecimal("999999999999999999999999")))
		}
	}

	private fun usage(
		actualModel: String = "provider/model",
		input: Long = 1_000,
		output: Long = 1_000,
		cacheRead: Long = 0,
		cacheWrite: Long = 0,
		reasoning: Long = 0,
		total: Long? = input + output,
		reportedCostUsd: BigDecimal? = BigDecimal("0.00368"),
	) = ProviderUsage(
		provider = "openrouter",
		requestedModel = "provider/requested-model",
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
