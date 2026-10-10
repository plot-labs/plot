package com.plot.api.artifact.workflow

import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelRole
import com.plot.api.ai.provider.ProviderUsage
import com.plot.api.billing.AiBillingBasis
import com.plot.api.billing.AiCreditCharge
import com.plot.api.persistence.SqlExecutor
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

/**
 * The billing side of artifact workflow model calls, kept in the `billing_status` columns of
 * `model_invocations`.
 *
 * A RUNNING call starts with no billing status. Recording usage makes it PENDING, publishing the
 * credits makes it SETTLED, and a call whose usage cannot be established becomes USAGE_UNKNOWN.
 * A workspace with a RUNNING call that is unbilled or PENDING is blocked from new provider work.
 *
 * The ledger does not check run ownership or own the call's lifecycle (RUNNING, FAILED, finished).
 * Claim-fenced writes go through [ArtifactWorkflowExecutionPersistence], which calls into it
 * inside its own transaction.
 */
@Repository
class ArtifactModelInvocationLedger(
	private val sqlExecutor: SqlExecutor,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
) {
	/** True while the workspace has a RUNNING call that is not yet billed or is waiting to settle. */
	fun hasUnresolved(workspaceId: UUID): Boolean =
		(sqlExecutor.queryForObject(
			"""select count(*) from model_invocations
				where workspace_id = ? and status = 'RUNNING' and (billing_status is null or billing_status = 'PENDING')""",
			Int::class.java,
			workspaceId,
		) ?: 0) > 0

	/** The oldest call in the workspace whose usage is recorded but whose credits are not published. */
	fun findPending(workspaceId: UUID): ArtifactModelInvocationSettlement? =
		findSettlement(workspaceId = workspaceId, billingStatus = "PENDING")

	/** A RUNNING call of this run that was billed but whose result was never checkpointed. */
	fun findSettledUnfinished(workspaceId: UUID, generationRunId: UUID): ArtifactModelInvocationSettlement? =
		findSettlement(
			workspaceId = workspaceId,
			billingStatus = "SETTLED",
			generationRunId = generationRunId,
			requireRunning = true,
		)

	fun findUnknownUsage(workspaceId: UUID, generationRunId: UUID): ModelInvocationLease? = sqlExecutor.query(
		"""
		select id, workflow_step_id, role, logical_call_index, attempt_no
		from model_invocations
		where workspace_id = ? and generation_run_id = ?
		  and status = 'RUNNING' and billing_status = 'USAGE_UNKNOWN'
		order by created_at, id
		limit 1
		""".trimIndent(),
		{ row, _ ->
			ModelInvocationLease(
				id = requireNotNull(row.getObject("id", UUID::class.java)),
				stepId = requireNotNull(row.getObject("workflow_step_id", UUID::class.java)),
				role = ModelRole.valueOf(requireNotNull(row.getString("role"))),
				logicalCallIndex = row.getInt("logical_call_index"),
				attemptNo = row.getInt("attempt_no"),
			)
		},
		workspaceId,
		generationRunId,
	).firstOrNull()

	/** Moves an unbilled RUNNING call to PENDING with its usage and charge. Returns the number of rows updated. */
	fun recordUsage(
		workspaceId: UUID,
		generationRunId: UUID,
		invocationId: UUID,
		metadata: ModelCallMetadata,
		usage: ProviderUsage,
		charge: AiCreditCharge,
		failureCode: String?,
	): Int = sqlExecutor.update(
		"""
		update model_invocations
		set billing_status = 'PENDING', provider = ?, model_name = ?, actual_model = ?, provider_request_id = ?,
		    prompt_token_count = ?, completion_token_count = ?, cache_read_token_count = ?,
		    cache_write_token_count = ?, reasoning_token_count = ?, total_token_count = ?,
		    provider_cost_usd = ?, credits = ?, billing_basis = ?, price_policy_version = ?, failure_code = ?,
		    result_metadata = ?::jsonb, latency_ms = ?
		where workspace_id = ? and generation_run_id = ? and id = ? and status = 'RUNNING' and billing_status is null
		""".trimIndent(),
		usage.provider, usage.requestedModel, usage.actualModel, usage.responseId,
		usage.inputTokens, usage.outputTokens, usage.cacheReadTokens ?: 0, usage.cacheWriteTokens ?: 0,
		usage.reasoningTokens ?: 0, usage.totalTokens, charge.providerCostUsd, charge.credits,
		charge.basis.name, charge.policyVersion, failureCode,
		objectMapper.writeValueAsString(metadata.observationAttributes), metadata.latency.toMillis().toInt(),
		workspaceId, generationRunId, invocationId,
	)

	/** Marks a PENDING call SETTLED. Settling an already settled call is a no-op. */
	fun markSettled(invocationId: UUID) {
		val updated = sqlExecutor.update(
			"update model_invocations set billing_status = 'SETTLED', billing_settled_at = ? where id = ? and billing_status = 'PENDING'",
			Timestamp.from(clock.instant()),
			invocationId,
		)
		if (updated != 1) {
			val status = sqlExecutor.queryForObject(
				"select billing_status from model_invocations where id = ?",
				String::class.java,
				invocationId,
			)
			check(status == "SETTLED") { "ArtifactWorkflow usage settlement state is stale" }
		}
	}

	/**
	 * Marks an unbilled RUNNING call USAGE_UNKNOWN, keeping whatever the provider did report.
	 * Returns the number of rows updated.
	 */
	fun markUsageUnknown(
		workspaceId: UUID,
		generationRunId: UUID,
		invocationId: UUID,
		metadata: ModelCallMetadata?,
	): Int = sqlExecutor.update(
		"""
		update model_invocations
		set billing_status = 'USAGE_UNKNOWN', provider_request_id = ?, result_metadata = ?::jsonb,
		    actual_model = ?, prompt_token_count = ?, completion_token_count = ?, total_token_count = ?,
		    cache_read_token_count = ?, cache_write_token_count = ?, reasoning_token_count = ?,
		    provider_cost_usd = ?, failure_code = 'AI_USAGE_UNKNOWN'
		where workspace_id = ? and generation_run_id = ? and id = ? and status = 'RUNNING' and billing_status is null
		""".trimIndent(),
		metadata?.responseId,
		objectMapper.writeValueAsString(metadata?.observationAttributes ?: emptyMap<String, String>()),
		metadata?.actualModel,
		metadata?.promptTokens,
		metadata?.completionTokens,
		metadata?.totalTokens,
		metadata?.cacheReadTokens,
		metadata?.cacheWriteTokens,
		metadata?.reasoningTokens,
		metadata?.reportedCostUsd,
		workspaceId,
		generationRunId,
		invocationId,
	)

	private fun findSettlement(
		workspaceId: UUID,
		billingStatus: String,
		generationRunId: UUID? = null,
		requireRunning: Boolean = false,
	): ArtifactModelInvocationSettlement? = sqlExecutor.query(
		"""
		select id, workspace_id, generation_run_id, workflow_step_id, role, logical_call_index, attempt_no,
		       provider, model_name, actual_model, provider_request_id, prompt_token_count,
		       completion_token_count, cache_read_token_count, cache_write_token_count,
		       reasoning_token_count, total_token_count, provider_cost_usd, credits, billing_basis,
		       price_policy_version, failure_code, latency_ms
		from model_invocations
		where workspace_id = ? and billing_status = ?
		  and (not ? or status = 'RUNNING')
		  and (?::uuid is null or generation_run_id = ?::uuid)
		order by created_at, id
		limit 1
		""".trimIndent(),
		{ row, _ ->
			ArtifactModelInvocationSettlement(
				id = requireNotNull(row.getObject("id", UUID::class.java)),
				workspaceId = requireNotNull(row.getObject("workspace_id", UUID::class.java)),
				generationRunId = requireNotNull(row.getObject("generation_run_id", UUID::class.java)),
				workflowStepId = requireNotNull(row.getObject("workflow_step_id", UUID::class.java)),
				role = ModelRole.valueOf(requireNotNull(row.getString("role"))),
				logicalCallIndex = row.getInt("logical_call_index"),
				attemptNo = row.getInt("attempt_no"),
				usage = ProviderUsage(
					provider = row.getString("provider"),
					requestedModel = row.getString("model_name"),
					actualModel = row.getString("actual_model"),
					responseId = row.getString("provider_request_id"),
					inputTokens = row.getLong("prompt_token_count"),
					outputTokens = row.getLong("completion_token_count"),
					cacheReadTokens = row.getLong("cache_read_token_count"),
					cacheWriteTokens = row.getLong("cache_write_token_count"),
					reasoningTokens = row.getLong("reasoning_token_count"),
					totalTokens = row.getLong("total_token_count"),
					reportedCostUsd = requireNotNull(row.getObject("provider_cost_usd", BigDecimal::class.java)),
				),
				charge = AiCreditCharge(
					providerCostUsd = requireNotNull(row.getObject("provider_cost_usd", BigDecimal::class.java)),
					credits = row.getLong("credits"),
					basis = AiBillingBasis.valueOf(requireNotNull(row.getString("billing_basis"))),
					policyVersion = requireNotNull(row.getString("price_policy_version")),
				),
				failureCode = row.getString("failure_code"),
				latencyMillis = row.getObject("latency_ms", Int::class.java),
			)
		},
		workspaceId,
		billingStatus,
		requireRunning,
		generationRunId?.toString(),
		generationRunId?.toString(),
	).firstOrNull()
}
