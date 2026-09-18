package com.plot.api.billing

import com.plot.api.ai.provider.ProviderUsage
import com.plot.api.config.PlotAiProperties
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PolarCreditServiceTest {
	private val workspaceId = UUID.fromString("22222222-2222-2222-2222-222222222222")
	private val provider = FakePolarCreditProvider()
	private val contexts = FakeWorkspaceBillingContextStore(workspaceId)
	private val service = PolarCreditService(properties(), provider, calculator(), contexts, PlotAiProperties())

	@Test
	fun preflightEnsuresWorkspaceCustomerGrantsTrialOnceAndRequiresPositiveBalance() {
		provider.balance = 3

		service.preflight(workspaceId)
		service.preflight(workspaceId)

		assertEquals(listOf(workspaceId, workspaceId), provider.ensured)
		assertEquals("cus_workspace", contexts.customerId)
		assertEquals(listOf(workspaceId, workspaceId), provider.grants)
		assertEquals(2, provider.balanceReads)
	}

	@Test
	fun zeroBalanceFailsClosedBeforeProviderWork() {
		provider.balance = 0

		val failure = assertFailsWith<AiCreditControlException> { service.preflight(workspaceId) }

		assertEquals("AI_CREDITS_EXHAUSTED", failure.safeCode)
		assertEquals(false, failure.recoverable)
	}

	@Test
	fun publishUsesInvocationIdentityAndAllowlistedUsageMetadata() {
		val invocationId = UUID.fromString("33333333-3333-3333-3333-333333333333")
		val usage = usage()
		val charge = service.calculate(usage)

		service.publish(workspaceId, invocationId, usage, charge)

		val event = provider.events.single()
		assertEquals(invocationId.toString(), event.eventId)
		assertEquals(6, event.credits)
		assertEquals("openai/gpt-5.4-nano", event.metadata["actual_model"])
		assertEquals("2026-09-18-v2-openrouter-cost", event.metadata["price_policy_version"])
		assertTrue(event.metadata.keys.none { it.contains("prompt") || it.contains("completion") || it.contains("authorization") })
	}

	@Test
	fun missingUsageFailsOnlyTheCurrentInvocation() {
		val failure = assertFailsWith<AiCreditControlException> {
			service.calculate(usage().copy(totalTokens = null))
		}

		assertEquals("AI_USAGE_UNKNOWN", failure.safeCode)
		assertEquals(false, failure.recoverable)
	}

	private fun properties() = PolarProperties(
		creditsEnabled = true,
		accessToken = "test",
		aiMeterId = "meter",
		trialCredits = 5_000,
		requestTimeout = Duration.ofSeconds(1),
	)

	private fun calculator() = AiCreditCalculator(AiCreditPolicy())

	private fun usage() = ProviderUsage(
		provider = "openrouter",
		requestedModel = "openai/gpt-5.4-nano",
		actualModel = "openai/gpt-5.4-nano",
		responseId = "response",
		inputTokens = 10,
		outputTokens = 10,
		cacheReadTokens = 0,
		cacheWriteTokens = 0,
		reasoningTokens = 0,
		totalTokens = 20,
		reportedCostUsd = BigDecimal("0.00368"),
	)
}

private class FakeWorkspaceBillingContextStore(private val workspaceId: UUID) : WorkspaceBillingContextStore {
	var customerId: String? = null
	override fun requireContext(workspaceId: UUID) = WorkspaceBillingContext(
		this.workspaceId,
		"Workspace",
		"owner@example.com",
		true,
		customerId,
	)

	override fun savePolarCustomerId(workspaceId: UUID, customerId: String) {
		this.customerId = customerId
	}
}

private class FakePolarCreditProvider : PolarCreditProvider {
	var balance = 1L
	var balanceReads = 0
	val ensured = mutableListOf<UUID>()
	val grants = mutableListOf<UUID>()
	val events = mutableListOf<FakePolarEvent>()

	override fun ensureCustomer(workspaceId: UUID, ownerEmail: String, workspaceName: String): PolarCustomer {
		ensured += workspaceId
		return PolarCustomer("cus_workspace", "plot-workspace:$workspaceId")
	}

	override fun readCreditBalance(workspaceId: UUID): Long {
		balanceReads++
		return balance
	}

	override fun grantTrialCredits(workspaceId: UUID): PolarEventResult {
		grants += workspaceId
		return PolarEventResult(1, 0)
	}

	override fun ingestCredits(workspaceId: UUID, eventId: String, credits: Long, metadata: Map<String, Any>): PolarEventResult {
		events += FakePolarEvent(eventId, credits, metadata)
		return PolarEventResult(1, 0)
	}
}

private data class FakePolarEvent(val eventId: String, val credits: Long, val metadata: Map<String, Any>)
