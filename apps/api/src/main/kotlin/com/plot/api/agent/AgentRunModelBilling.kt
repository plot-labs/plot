package com.plot.api.agent

import com.plot.api.ai.provider.ProviderUsage
import com.plot.api.billing.AiCreditCharge
import com.plot.api.billing.AiCreditControlException
import com.plot.api.billing.PolarCreditService
import java.util.UUID

/**
 * Bills the model calls of one claimed agent run turn.
 *
 * Each call is reserved before the provider is contacted and settled after it
 * answers, so a crash between the two leaves a ledger row the next attempt can
 * finish. With credits disabled it only counts the call against the run budget.
 */
internal class AgentRunModelBilling(
	private val claim: ClaimedAgentRun,
	private val run: AgentRunRecord,
	private val maxModelCalls: Int,
	private val executionPersistence: AgentRunExecutionPersistence,
	private val ledger: AgentModelInvocationLedger,
	private val creditService: PolarCreditService,
) {
	private var activeInvocationId: UUID? = null

	/** Reserves the next model call, first finishing any settlement an earlier attempt left open. */
	fun beginModelCall() {
		if (!creditService.enabled) {
			executionPersistence.beginModelDecision(claim, maxModelCalls)
			return
		}
		recoverSettlement()
		creditService.preflight(run.workspaceId)
		try {
			activeInvocationId = executionPersistence.beginBilledModelInvocation(claim, maxModelCalls)
		} catch (failure: AgentModelInvocationBlockedException) {
			throw AiCreditControlException("AI_CREDIT_SETTLEMENT_PENDING", true, "Workspace AI usage is pending", failure)
		}
	}

	/** Records the provider's usage, publishes the credits, and marks the call settled. */
	fun settleModelCall(usage: ProviderUsage) {
		if (!creditService.enabled) return
		val invocationId = activeInvocationId
			?: throw AiCreditControlException("AI_USAGE_UNKNOWN", false, "AI invocation identity is unavailable")
		val charge: AiCreditCharge = try {
			creditService.calculate(usage)
		} catch (failure: AiCreditControlException) {
			ledger.markUsageUnknown(invocationId)
			activeInvocationId = null
			throw failure
		}
		executionPersistence.recordModelInvocationUsage(claim, invocationId, usage, charge)
		creditService.publish(run.workspaceId, invocationId, usage, charge)
		ledger.markSettled(invocationId)
		activeInvocationId = null
	}

	/** Closes the reserved call after the provider failed without billable usage. */
	fun abortModelCall() {
		activeInvocationId?.let(ledger::markAborted)
		activeInvocationId = null
	}

	/**
	 * Finishes whatever an interrupted attempt left in the ledger. Recovery always
	 * ends the current attempt with an exception: provider work already paid for
	 * must not be repeated in the same turn.
	 */
	private fun recoverSettlement() {
		if (ledger.hasSettledUnapplied(run.workspaceId, run.id)) {
			throw AiCreditControlException(
				"AI_SETTLEMENT_RECOVERED",
				false,
				"Previous model usage was settled without repeating provider work",
			)
		}
		ledger.resolveOrphaned(run.workspaceId)
		val unresolved = ledger.findUnresolved(run.workspaceId) ?: return
		when (unresolved.status) {
			AgentModelInvocationStatus.PENDING -> {
				val usage = requireNotNull(unresolved.usage)
				val charge = AiCreditCharge(
					providerCostUsd = requireNotNull(unresolved.providerCostUsd),
					credits = requireNotNull(unresolved.credits),
					basis = requireNotNull(unresolved.billingBasis),
					policyVersion = requireNotNull(unresolved.pricePolicyVersion),
				)
				creditService.publish(run.workspaceId, unresolved.id, usage, charge)
				ledger.markSettled(unresolved.id)
				if (unresolved.agentRunId == run.id) {
					throw AiCreditControlException(
						"AI_SETTLEMENT_RECOVERED",
						false,
						"Previous model usage was settled without repeating provider work",
					)
				}
				throw AiCreditControlException(
					"AI_CREDIT_SETTLEMENT_PENDING",
					true,
					"Previous workspace usage was settled; retry before new provider work",
				)
			}
			AgentModelInvocationStatus.STARTED -> {
				if (unresolved.agentRunId == run.id) {
					ledger.markUsageUnknown(unresolved.id)
					throw AiCreditControlException("AI_USAGE_UNKNOWN", false, "Previous provider usage is unavailable")
				}
				throw AiCreditControlException("AI_CREDIT_SETTLEMENT_PENDING", true, "Workspace AI usage is in progress")
			}
			else -> Unit
		}
	}
}
