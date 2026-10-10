package com.plot.api.agent

import com.plot.api.ai.provider.ProviderUsage
import com.plot.api.billing.AiBillingBasis
import com.plot.api.billing.AiCreditCharge
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.SqlRow
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * The billing ledger for agent model calls, one `agent_model_invocations` row per call.
 *
 * A row moves STARTED -> PENDING (usage recorded) -> SETTLED (credits published), or leaves
 * through ABORTED / USAGE_UNKNOWN. A workspace may hold only one STARTED or PENDING row at a
 * time, so an unresolved row blocks new provider work until it is settled or resolved.
 *
 * The ledger does not check run ownership. Claim-fenced writes go through
 * [AgentRunExecutionPersistence], which calls into it inside its own transaction.
 */
@Component
class AgentModelInvocationLedger(
	private val sqlExecutor: SqlExecutor,
	private val clock: Clock? = null,
) {
	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	/** The oldest STARTED or PENDING call in the workspace, if any. */
	fun findUnresolved(workspaceId: UUID): AgentModelInvocationSettlement? = sqlExecutor.query(
		"""
		select id, workspace_id, agent_run_id, sequence_no, status, provider, requested_model, actual_model,
		       provider_response_id, input_token_count, output_token_count, cache_read_token_count,
		       cache_write_token_count, reasoning_token_count, total_token_count, provider_cost_usd,
		       credits, billing_basis, price_policy_version
		from agent_model_invocations
		where workspace_id = ? and status in ('STARTED', 'PENDING')
		order by created_at, id
		limit 1
		""".trimIndent(),
		{ row, _ -> row.toAgentModelInvocation() },
		workspaceId,
	).firstOrNull()

	/** True while the workspace has any STARTED or PENDING call. */
	fun hasUnresolved(workspaceId: UUID): Boolean =
		(sqlExecutor.queryForObject(
			"select count(*) from agent_model_invocations where workspace_id = ? and status in ('STARTED', 'PENDING')",
			Int::class.java,
			workspaceId,
		) ?: 0) > 0

	/** True when the run was billed for a call whose output was never applied to a step. */
	fun hasSettledUnapplied(workspaceId: UUID, agentRunId: UUID): Boolean =
		(sqlExecutor.queryForObject(
			"""select count(*) from agent_model_invocations
			where workspace_id = ? and agent_run_id = ? and status = 'SETTLED' and output_applied_at is null""",
			Int::class.java,
			workspaceId,
			agentRunId,
		) ?: 0) > 0

	fun insertStarted(invocationId: UUID, workspaceId: UUID, agentRunId: UUID, sequence: Int, now: Instant) {
		sqlExecutor.update(
			"""insert into agent_model_invocations
			(id, workspace_id, agent_run_id, sequence_no, status, created_at)
			values (?, ?, ?, ?, 'STARTED', ?)""",
			invocationId, workspaceId, agentRunId, sequence, Timestamp.from(now),
		)
	}

	/** Moves a STARTED call to PENDING with its usage and charge. Returns the number of rows updated. */
	fun recordUsage(
		workspaceId: UUID,
		agentRunId: UUID,
		invocationId: UUID,
		usage: ProviderUsage,
		charge: AiCreditCharge,
		now: Instant,
	): Int = sqlExecutor.update(
		"""
		update agent_model_invocations
		set status = 'PENDING', provider = ?, requested_model = ?, actual_model = ?, provider_response_id = ?,
		    input_token_count = ?, output_token_count = ?, cache_read_token_count = ?, cache_write_token_count = ?,
		    reasoning_token_count = ?, total_token_count = ?, provider_cost_usd = ?, credits = ?,
		    billing_basis = ?, price_policy_version = ?, usage_recorded_at = ?
		where workspace_id = ? and agent_run_id = ? and id = ? and status = 'STARTED'
		""".trimIndent(),
		usage.provider, usage.requestedModel, usage.actualModel, usage.responseId,
		usage.inputTokens, usage.outputTokens, usage.cacheReadTokens ?: 0, usage.cacheWriteTokens ?: 0,
		usage.reasoningTokens ?: 0, usage.totalTokens, charge.providerCostUsd, charge.credits,
		charge.basis.name, charge.policyVersion, Timestamp.from(now),
		workspaceId, agentRunId, invocationId,
	)

	/** Marks a PENDING call SETTLED. Settling an already settled call is a no-op. */
	fun markSettled(invocationId: UUID, now: Instant = currentInstant()) {
		val updated = sqlExecutor.update(
			"update agent_model_invocations set status = 'SETTLED', settled_at = ? where id = ? and status = 'PENDING'",
			Timestamp.from(now), invocationId,
		)
		if (updated != 1) {
			val status = sqlExecutor.queryForObject("select status from agent_model_invocations where id = ?", String::class.java, invocationId)
			if (status != "SETTLED") throw AgentRunStateException("Agent usage settlement state is stale")
		}
	}

	fun markSettledApplied(workspaceId: UUID, agentRunId: UUID, now: Instant = currentInstant()) {
		sqlExecutor.update(
			"""update agent_model_invocations set output_applied_at = ?
			where workspace_id = ? and agent_run_id = ? and status = 'SETTLED' and output_applied_at is null""",
			Timestamp.from(now),
			workspaceId,
			agentRunId,
		)
	}

	fun markAborted(invocationId: UUID) {
		sqlExecutor.update("update agent_model_invocations set status = 'ABORTED' where id = ? and status = 'STARTED'", invocationId)
	}

	fun markUsageUnknown(invocationId: UUID) {
		sqlExecutor.update("update agent_model_invocations set status = 'USAGE_UNKNOWN' where id = ? and status = 'STARTED'", invocationId)
	}

	/**
	 * A STARTED invocation whose run already finished can never be settled by that run, and it would
	 * otherwise hold the workspace-wide unresolved slot forever. PENDING rows keep their recorded usage
	 * and stay for the next run to settle.
	 */
	fun resolveOrphaned(workspaceId: UUID): Int =
		sqlExecutor.update(RESOLVE_ORPHANED_AGENT_MODEL_INVOCATIONS_SQL, workspaceId)
}

/** Marks STARTED agent model calls of already finished runs as USAGE_UNKNOWN for one workspace. */
private val RESOLVE_ORPHANED_AGENT_MODEL_INVOCATIONS_SQL = """
	update agent_model_invocations invocation
	set status = 'USAGE_UNKNOWN'
	where invocation.workspace_id = ? and invocation.status = 'STARTED'
	  and exists (
	    select 1 from agent_runs run
	    where run.workspace_id = invocation.workspace_id and run.id = invocation.agent_run_id
	      and run.status in ('SUCCEEDED', 'FAILED')
	  )
""".trimIndent()

private fun SqlRow.toAgentModelInvocation(): AgentModelInvocationSettlement {
	val status = AgentModelInvocationStatus.valueOf(requireNotNull(getString("status")))
	val usage = if (status == AgentModelInvocationStatus.PENDING) ProviderUsage(
		provider = getString("provider"),
		requestedModel = getString("requested_model"),
		actualModel = getString("actual_model"),
		responseId = getString("provider_response_id"),
		inputTokens = getObject("input_token_count", Long::class.javaObjectType),
		outputTokens = getObject("output_token_count", Long::class.javaObjectType),
		cacheReadTokens = getObject("cache_read_token_count", Long::class.javaObjectType),
		cacheWriteTokens = getObject("cache_write_token_count", Long::class.javaObjectType),
		reasoningTokens = getObject("reasoning_token_count", Long::class.javaObjectType),
		totalTokens = getObject("total_token_count", Long::class.javaObjectType),
		reportedCostUsd = getObject("provider_cost_usd", java.math.BigDecimal::class.java),
	) else null
	return AgentModelInvocationSettlement(
		id = requireNotNull(getObject("id", UUID::class.java)),
		workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
		agentRunId = requireNotNull(getObject("agent_run_id", UUID::class.java)),
		sequence = getInt("sequence_no"),
		status = status,
		usage = usage,
		providerCostUsd = getObject("provider_cost_usd", java.math.BigDecimal::class.java),
		credits = getObject("credits", Long::class.javaObjectType),
		billingBasis = getString("billing_basis")?.let(AiBillingBasis::valueOf),
		pricePolicyVersion = getString("price_policy_version"),
	)
}
