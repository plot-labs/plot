package com.plot.api.migration

import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.JooqSqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SignalActivityChatBackfillService(
	private val sqlExecutor: JooqSqlExecutor,
	private val uuidGenerator: UuidGenerator,
) {
	data class LagReport(
		val unreconciledRuns: Long,
		val unreconciledOpportunities: Long,
		val unreconciledSignals: Long,
		val totalLag: Long,
	)

	data class BackfillReport(
		val checkpointKey: String,
		val watermark: Instant,
		val reconciledRuns: Int,
		val reconciledOpportunities: Int,
		val reconciledSignals: Int,
		val lag: LagReport,
		val status: String,
	)

	@Transactional
	fun runBackfill(
		checkpointKey: String = "chat_activity_backfill",
		watermark: Instant = Instant.now(),
	): BackfillReport {
		// 1. Reconcile Chat-visible runs into turns, response versions, and envelopes
		val runsCount = reconcileChatRuns(watermark)

		// 2. Reconcile historical opportunities into legacy activity provenance
		val oppsCount = reconcileOpportunities(watermark)

		// 3. Reconcile historical signals into signal evaluations
		val signalsCount = reconcileSignals(watermark)

		// 4. Compute remaining lag at watermark
		val lag = computeLag(watermark)
		val status = if (lag.totalLag == 0L) "COMPLETED" else "IN_PROGRESS"

		// 5. Update or insert checkpoint
		val totalReconciled = (runsCount + oppsCount + signalsCount).toLong()
		sqlExecutor.update(
			"""
			insert into signal_activity_chat_backfill_checkpoints (
				checkpoint_key, high_water_mark, status, reconciled_count, lag_count, updated_at
			) values (?, ?, ?, ?, ?, now())
			on conflict (checkpoint_key) do update
			set high_water_mark = excluded.high_water_mark,
			    status = excluded.status,
			    reconciled_count = signal_activity_chat_backfill_checkpoints.reconciled_count + excluded.reconciled_count,
			    lag_count = excluded.lag_count,
			    updated_at = now()
			""".trimIndent(),
			checkpointKey,
			Timestamp.from(watermark),
			status,
			totalReconciled,
			lag.totalLag,
		)

		return BackfillReport(
			checkpointKey = checkpointKey,
			watermark = watermark,
			reconciledRuns = runsCount,
			reconciledOpportunities = oppsCount,
			reconciledSignals = signalsCount,
			lag = lag,
			status = status,
		)
	}

	fun computeLag(watermark: Instant): LagReport {
		val unreconciledRuns = sqlExecutor.queryForObject(
			"""
			select count(*)
			from agent_runs
			where created_at <= ?
			  and id not in (select agent_run_id from chat_response_versions)
			""".trimIndent(),
			Long::class.java,
			Timestamp.from(watermark),
		) ?: 0L

		val unreconciledOpps = sqlExecutor.queryForObject(
			"""
			select count(*)
			from autonomy_opportunities
			where created_at <= ?
			  and id not in (select opportunity_id from legacy_activity_provenance where opportunity_id is not null)
			""".trimIndent(),
			Long::class.java,
			Timestamp.from(watermark),
		) ?: 0L

		val unreconciledSignals = sqlExecutor.queryForObject(
			"""
			select count(*)
			from autonomy_signals
			where received_at <= ?
			  and id not in (select signal_id from signal_evaluations)
			""".trimIndent(),
			Long::class.java,
			Timestamp.from(watermark),
		) ?: 0L

		return LagReport(
			unreconciledRuns = unreconciledRuns,
			unreconciledOpportunities = unreconciledOpps,
			unreconciledSignals = unreconciledSignals,
			totalLag = unreconciledRuns + unreconciledOpps + unreconciledSignals,
		)
	}

	fun reconcileChatRuns(watermark: Instant): Int {
		val runs = sqlExecutor.query(
			"""
			select id, workspace_id, work_session_id, created_by_user_id, instruction_snapshot,
			       prompt_version, tool_policy_version, request_fingerprint, status, created_at, updated_at
			from agent_runs
			where created_at <= ?
			  and id not in (select agent_run_id from chat_response_versions)
			order by work_session_id, created_at asc, id asc
			""".trimIndent(),
			{ rs, _ ->
				HistoricalRun(
					id = requireNotNull(rs.getObject("id", UUID::class.java)),
					workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
					workSessionId = requireNotNull(rs.getObject("work_session_id", UUID::class.java)),
					userId = rs.getObject("created_by_user_id", UUID::class.java),
					instruction = rs.getString("instruction_snapshot") ?: "",
					promptVersion = rs.getString("prompt_version") ?: "v1",
					toolPolicyVersion = rs.getString("tool_policy_version") ?: "v1",
					requestFingerprint = rs.getString("request_fingerprint") ?: "legacy",
					status = rs.getString("status") ?: "SUCCEEDED",
					createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
					updatedAt = requireNotNull(rs.getTimestamp("updated_at") ?: rs.getTimestamp("created_at")).toInstant(),
				)
			},
			Timestamp.from(watermark),
		)

		var backfilledCount = 0
		for (run in runs) {
			val nextTurnIndex = sqlExecutor.queryForObject(
				"select coalesce(max(turn_index) + 1, 0) from chat_turns where workspace_id = ? and work_session_id = ?",
				Int::class.java,
				run.workspaceId,
				run.workSessionId,
			) ?: 0

			val turnId = uuidGenerator.next()
			sqlExecutor.update(
				"""
				insert into chat_turns (
					id, workspace_id, work_session_id, turn_index, user_message, created_by_user_id, created_at, updated_at
				) values (?, ?, ?, ?, ?, ?, ?, ?)
				on conflict (workspace_id, work_session_id, turn_index) do nothing
				""".trimIndent(),
				turnId,
				run.workspaceId,
				run.workSessionId,
				nextTurnIndex,
				run.instruction.ifBlank { "Legacy Turn" },
				run.userId,
				Timestamp.from(run.createdAt),
				Timestamp.from(run.updatedAt),
			)

			val resolvedTurnId = sqlExecutor.queryForObject(
				"select id from chat_turns where workspace_id = ? and work_session_id = ? and turn_index = ?",
				UUID::class.java,
				run.workspaceId,
				run.workSessionId,
				nextTurnIndex,
			) ?: turnId

			val isActive = run.status in setOf("QUEUED", "RUNNING")
			val versionId = uuidGenerator.next()
			sqlExecutor.update(
				"""
				insert into chat_response_versions (
					id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id,
					lineage_parent_version_id, is_active, created_at, updated_at
				) values (?, ?, ?, 0, ?, ?, null, ?, ?, ?)
				on conflict (workspace_id, agent_run_id) do nothing
				""".trimIndent(),
				versionId,
				run.workspaceId,
				resolvedTurnId,
				run.id,
				run.userId,
				isActive,
				Timestamp.from(run.createdAt),
				Timestamp.from(run.updatedAt),
			)

			val envelopeId = uuidGenerator.next()
			val settingsJson = """{"promptVersion":"${run.promptVersion}","toolPolicyVersion":"${run.toolPolicyVersion}"}"""
			sqlExecutor.update(
				"""
				insert into chat_execution_envelopes (
					id, workspace_id, agent_run_id, fingerprint_version, envelope_fingerprint,
					generation_settings, source_snapshot_id, created_at
				) values (?, ?, ?, 1, ?, ?::jsonb, null, ?)
				on conflict (workspace_id, agent_run_id) do nothing
				""".trimIndent(),
				envelopeId,
				run.workspaceId,
				run.id,
				run.requestFingerprint,
				settingsJson,
				Timestamp.from(run.createdAt),
			)
			backfilledCount++
		}
		return backfilledCount
	}

	fun reconcileOpportunities(watermark: Instant): Int {
		val opps = sqlExecutor.query(
			"""
			select o.id, o.workspace_id, o.source_scope_id, o.title, o.disposition, o.reason,
			       o.missing_facts, o.last_error_code, o.dismissed, o.created_at, o.updated_at,
			       g.id as goal_id, g.agent_run_id
			from autonomy_opportunities o
			left join autonomy_goals g on g.workspace_id = o.workspace_id and g.opportunity_id = o.id
			where o.created_at <= ?
			  and o.id not in (select opportunity_id from legacy_activity_provenance where opportunity_id is not null)
			order by o.created_at asc, o.id asc
			""".trimIndent(),
			{ rs, _ ->
				HistoricalOpportunity(
					id = requireNotNull(rs.getObject("id", UUID::class.java)),
					workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
					sourceScopeId = requireNotNull(rs.getObject("source_scope_id", UUID::class.java)),
					title = rs.getString("title") ?: "Legacy Opportunity",
					disposition = rs.getString("disposition") ?: "AWAITING_EVIDENCE",
					reason = rs.getString("reason") ?: "Historical opportunity",
					missingFacts = rs.getString("missing_facts") ?: "[]",
					lastErrorCode = rs.getString("last_error_code"),
					dismissed = rs.getBoolean("dismissed"),
					createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
					updatedAt = requireNotNull(rs.getTimestamp("updated_at") ?: rs.getTimestamp("created_at")).toInstant(),
					goalId = rs.getObject("goal_id", UUID::class.java),
					agentRunId = rs.getObject("agent_run_id", UUID::class.java),
				)
			},
			Timestamp.from(watermark),
		)

		var backfilledCount = 0
		for (opp in opps) {
			val disposition = when {
				opp.dismissed || opp.disposition == "EXCLUDED" -> "EXCLUDED"
				opp.agentRunId != null -> "ADMITTED"
				else -> "NO_GENERATION"
			}
			val provId = uuidGenerator.next()
			sqlExecutor.update(
				"""
				insert into legacy_activity_provenance (
					id, workspace_id, source_scope_id, opportunity_id, goal_id, task_id,
					agent_run_id, chat_id, title, disposition, reason, dismissed,
					missing_facts, last_error_code, has_exact_signal_link, uncertainty_label,
					semantic_time, created_at
				) values (
					?, ?, ?, ?, ?, null,
					?, null, ?, ?, ?, ?,
					?::jsonb, ?, false, 'MIGRATED_HISTORICAL_RECORD',
					?, ?
				)
				on conflict (workspace_id, id) do nothing
				""".trimIndent(),
				provId,
				opp.workspaceId,
				opp.sourceScopeId,
				opp.id,
				opp.goalId,
				opp.agentRunId,
				opp.title,
				disposition,
				opp.reason,
				opp.dismissed,
				opp.missingFacts,
				opp.lastErrorCode,
				Timestamp.from(opp.updatedAt),
				Timestamp.from(opp.createdAt),
			)
			backfilledCount++
		}
		return backfilledCount
	}

	fun reconcileSignals(watermark: Instant): Int {
		val signals = sqlExecutor.query(
			"""
			select id, workspace_id, source_namespace_id, source_scope_id, delivery_key, received_at
			from autonomy_signals
			where received_at <= ?
			  and id not in (select signal_id from signal_evaluations)
			order by received_at asc, id asc
			""".trimIndent(),
			{ rs, _ ->
				HistoricalSignal(
					id = requireNotNull(rs.getObject("id", UUID::class.java)),
					workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
					namespaceId = requireNotNull(rs.getObject("source_namespace_id", UUID::class.java)),
					scopeId = requireNotNull(rs.getObject("source_scope_id", UUID::class.java)),
					deliveryKey = rs.getString("delivery_key") ?: "",
					receivedAt = requireNotNull(rs.getTimestamp("received_at")).toInstant(),
				)
			},
			Timestamp.from(watermark),
		)

		var backfilledCount = 0
		for (sig in signals) {
			val evalId = uuidGenerator.next()
			sqlExecutor.update(
				"""
				insert into signal_evaluations (
					id, workspace_id, signal_id, source_namespace_id, source_scope_id,
					input_fingerprint, outcome, reason, semantic_time, created_at, updated_at
				) values (
					?, ?, ?, ?, ?,
					?, 'NO_GENERATION', 'Historical signal backfill', ?, ?, ?
				)
				on conflict (workspace_id, signal_id) do nothing
				""".trimIndent(),
				evalId,
				sig.workspaceId,
				sig.id,
				sig.namespaceId,
				sig.scopeId,
				sig.deliveryKey,
				Timestamp.from(sig.receivedAt),
				Timestamp.from(sig.receivedAt),
				Timestamp.from(sig.receivedAt),
			)
			backfilledCount++
		}
		return backfilledCount
	}

	private data class HistoricalRun(
		val id: UUID,
		val workspaceId: UUID,
		val workSessionId: UUID,
		val userId: UUID?,
		val instruction: String,
		val promptVersion: String,
		val toolPolicyVersion: String,
		val requestFingerprint: String,
		val status: String,
		val createdAt: Instant,
		val updatedAt: Instant,
	)

	private data class HistoricalOpportunity(
		val id: UUID,
		val workspaceId: UUID,
		val sourceScopeId: UUID,
		val title: String,
		val disposition: String,
		val reason: String,
		val missingFacts: String,
		val lastErrorCode: String?,
		val dismissed: Boolean,
		val createdAt: Instant,
		val updatedAt: Instant,
		val goalId: UUID?,
		val agentRunId: UUID?,
	)

	private data class HistoricalSignal(
		val id: UUID,
		val workspaceId: UUID,
		val namespaceId: UUID,
		val scopeId: UUID,
		val deliveryKey: String,
		val receivedAt: Instant,
	)
}
