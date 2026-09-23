package com.plot.api.billing

import com.plot.api.ai.provider.ProviderUsage
import com.plot.api.config.PlotAiProperties
import com.plot.api.workspace.UserRepository
import com.plot.api.workspace.WorkspaceRepository
import java.util.UUID
import org.springframework.stereotype.Component

data class WorkspaceBillingContext(
	val workspaceId: UUID,
	val workspaceName: String,
	val ownerEmail: String,
	val entitlementStatus: String,
	val polarCustomerId: String?,
)

interface WorkspaceBillingContextStore {
	fun requireContext(workspaceId: UUID): WorkspaceBillingContext
	fun savePolarCustomerId(workspaceId: UUID, customerId: String)
}

@Component
class RepositoryWorkspaceBillingContextStore(
	private val workspaces: WorkspaceRepository,
	private val users: UserRepository,
) : WorkspaceBillingContextStore {
	override fun requireContext(workspaceId: UUID): WorkspaceBillingContext {
		val workspace = workspaces.findById(workspaceId).orElseThrow { IllegalArgumentException("Workspace not found") }
		val owner = workspace.createdByUserId?.let { users.findById(it).orElse(null) }
			?: throw IllegalArgumentException("Workspace billing owner not found")
		return WorkspaceBillingContext(
			workspace.id,
			workspace.name,
			owner.email,
			workspace.entitlementStatus,
			workspace.polarCustomerId,
		)
	}

	override fun savePolarCustomerId(workspaceId: UUID, customerId: String) {
		val workspace = workspaces.findById(workspaceId).orElseThrow { IllegalArgumentException("Workspace not found") }
		if (workspace.polarCustomerId == customerId) return
		workspace.polarCustomerId = customerId
		workspaces.save(workspace)
	}
}

class AiCreditControlException(
	val safeCode: String,
	val recoverable: Boolean,
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

@Component
class PolarCreditService(
	private val properties: PolarProperties,
	private val provider: PolarCreditProvider,
	private val calculator: AiCreditCalculator,
	private val contexts: WorkspaceBillingContextStore,
	aiProperties: PlotAiProperties,
) {
	val enabled: Boolean = properties.creditsEnabled

	init {
		if (aiProperties.enabled) {
			require(enabled) { "Polar credits must be enabled when AI is enabled" }
		}
	}

	fun preflight(workspaceId: UUID) {
		if (!enabled) return
		try {
			val context = contexts.requireContext(workspaceId)
			val customer = provider.ensureCustomer(
				workspaceId,
				context.ownerEmail,
				context.workspaceName,
				context.polarCustomerId,
			)
			if (context.polarCustomerId != customer.id) contexts.savePolarCustomerId(workspaceId, customer.id)
			if (provider.readCreditBalance(workspaceId) < 1) {
				throw AiCreditControlException("AI_CREDITS_EXHAUSTED", false, "Workspace AI credits are exhausted")
			}
		} catch (failure: AiCreditControlException) {
			throw failure
		} catch (failure: PolarApiException) {
			throw AiCreditControlException(
				if (failure.retryable) "AI_CREDIT_CHECK_UNAVAILABLE" else failure.safeCode,
				failure.retryable,
				"AI credit balance could not be verified",
				failure,
			)
		}
	}

	fun calculate(usage: ProviderUsage): AiCreditCharge = try {
		calculator.calculate(usage)
	} catch (failure: AiUsageUnknownException) {
		throw AiCreditControlException("AI_USAGE_UNKNOWN", false, "Provider usage is unavailable", failure)
	}

	fun readOverview(workspaceId: UUID): PolarCreditOverview {
		if (!enabled) return PolarCreditOverview.empty()
		return try {
			provider.readCreditOverview(workspaceId)
		} catch (failure: PolarApiException) {
			throw AiCreditControlException(
				"AI_CREDIT_CHECK_UNAVAILABLE",
				failure.retryable,
				"AI credit balance could not be loaded",
				failure,
			)
		}
	}

	fun publish(workspaceId: UUID, invocationId: UUID, usage: ProviderUsage, charge: AiCreditCharge) {
		if (!enabled) return
		val metadata = linkedMapOf<String, Any>(
			"invocation_id" to invocationId.toString(),
			"provider" to usage.provider.orEmpty(),
			"requested_model" to usage.requestedModel.orEmpty(),
			"actual_model" to usage.actualModel.orEmpty(),
			"response_id" to usage.responseId.orEmpty(),
			"input_tokens" to requireNotNull(usage.inputTokens),
			"output_tokens" to requireNotNull(usage.outputTokens),
			"cache_read_tokens" to (usage.cacheReadTokens ?: 0),
			"cache_write_tokens" to (usage.cacheWriteTokens ?: 0),
			"reasoning_tokens" to (usage.reasoningTokens ?: 0),
			"total_tokens" to requireNotNull(usage.totalTokens),
			"provider_cost_usd" to charge.providerCostUsd.toPlainString(),
			"billing_basis" to charge.basis.name.lowercase(),
			"price_policy_version" to charge.policyVersion,
		)
		try {
			provider.ingestCredits(workspaceId, invocationId.toString(), charge.credits, metadata)
		} catch (failure: PolarApiException) {
			throw AiCreditControlException(
				"AI_CREDIT_SETTLEMENT_PENDING",
				true,
				"AI usage settlement is pending",
				failure,
			)
		}
	}
}
