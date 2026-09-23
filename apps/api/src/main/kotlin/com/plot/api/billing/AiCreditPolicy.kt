package com.plot.api.billing

import java.math.BigDecimal

data class AiCreditPolicy(
	val version: String = "2026-09-18-v2-openrouter-cost",
	val creditUnitUsd: BigDecimal = BigDecimal("0.001"),
	val debitMultiplier: BigDecimal = BigDecimal("1.5"),
	val polarFeeRate: BigDecimal = BigDecimal("0.05"),
	val polarFixedFeeUsd: BigDecimal = BigDecimal("0.50"),
	val providerFundingFeeRate: BigDecimal = BigDecimal("0.055"),
	val targetAiGrossMarginRate: BigDecimal = BigDecimal("0.70"),
) {
	init {
		require(version.isNotBlank())
		require(creditUnitUsd.compareTo(BigDecimal("0.001")) == 0) { "AI credit unit is a fixed product constant" }
		require(debitMultiplier.compareTo(BigDecimal("1.5")) == 0) { "AI debit multiplier is a fixed product constant" }
	}
}
