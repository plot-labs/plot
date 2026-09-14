package com.plot.api.migration

import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Operator-triggered reconciliation. A run captures one watermark, then
 * advances phase checkpoints in committed UUID-keyset batches. Compatibility
 * writes after that snapshot belong to a later, separately named catch-up run.
 */
@Service
class SignalActivityChatBackfillService(
	private val sql: JooqSqlExecutor,
	private val transactions: JooqTransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val objectMapper: ObjectMapper,
) {
	data class LagReport(
		val unreconciledRuns: Long,
		val unreconciledOpportunities: Long,
		val unreconciledSignals: Long,
		val totalLag: Long,
	)

	data class PhaseReport(
		val phase: String,
		val status: String,
		val attemptedCount: Long,
		val insertedCount: Long,
		val skippedCount: Long,
		val lastProcessedId: UUID?,
	)

	data class BackfillReport(
		val checkpointKey: String,
		val watermark: Instant?,
		val attemptedCount: Long,
		val insertedCount: Long,
		val skippedCount: Long,
		val lag: LagReport?,
		val status: String,
		val leaseAcquired: Boolean,
		val phases: List<PhaseReport>,
	)

	/**
	 * Runs at most [maxBatches] committed batches. The initial watermark is only
	 * used when [checkpointKey] is created and is never replaced on restart.
	 */
	fun run(
		checkpointKey: String = DEFAULT_CHECKPOINT_KEY,
		requestedBatchSize: Int = DEFAULT_BATCH_SIZE,
		maxBatches: Int = DEFAULT_MAX_BATCHES,
		leaseDuration: Duration = DEFAULT_LEASE_DURATION,
		initialWatermark: Instant = Instant.now(),
	): BackfillReport {
		validateRequest(checkpointKey, requestedBatchSize, maxBatches, leaseDuration)
		if (ensureCheckpoints(checkpointKey, initialWatermark).status == COMPLETED) return report(checkpointKey, false)

		val owner = uuidGenerator.next().toString()
		if (!claimLease(checkpointKey, owner, leaseDuration)) return report(checkpointKey, false)
		try {
			var completedBatches = 0
			while (completedBatches < maxBatches) {
				val phase = nextIncompletePhase(checkpointKey) ?: break
				processBatch(checkpointKey, phase, owner, requestedBatchSize, leaseDuration)
				completedBatches++
			}
			if (nextIncompletePhase(checkpointKey) == null) finalizeRun(checkpointKey, owner)
			return report(checkpointKey, true)
		} catch (failure: RuntimeException) {
			markFailed(checkpointKey, owner)
			throw failure
		} finally {
			releaseLease(checkpointKey, owner)
		}
	}

	fun status(checkpointKey: String = DEFAULT_CHECKPOINT_KEY): BackfillReport {
		validateCheckpointKey(checkpointKey)
		return report(checkpointKey, false)
	}

	private fun ensureCheckpoints(checkpointKey: String, initialWatermark: Instant): BackfillCheckpoint =
		transactions.executeRequiresNew {
			val now = Timestamp.from(Instant.now())
			sql.update(
				"""
				insert into signal_activity_chat_backfill_checkpoints
				(checkpoint_key, high_water_mark, status, updated_at)
				values (?, ?, 'IN_PROGRESS', ?)
				on conflict (checkpoint_key) do nothing
				""".trimIndent(), checkpointKey, Timestamp.from(initialWatermark), now,
			)
			val root = requireNotNull(readCheckpoint(checkpointKey, true))
			PHASES.forEach { phase ->
				sql.update(
					"""
					insert into signal_activity_chat_backfill_checkpoints
					(checkpoint_key, high_water_mark, status, updated_at)
					values (?, ?, 'PENDING', ?)
					on conflict (checkpoint_key) do nothing
					""".trimIndent(), phaseKey(checkpointKey, phase), Timestamp.from(root.watermark), now,
				)
			}
			root
		}

	private fun claimLease(checkpointKey: String, owner: String, duration: Duration): Boolean =
		transactions.executeRequiresNew {
			val now = Instant.now()
			sql.update(
				"""
				update signal_activity_chat_backfill_checkpoints
				set status = 'IN_PROGRESS', lease_owner = ?, lease_expires_at = ?, updated_at = ?
				where checkpoint_key = ? and status <> 'COMPLETED'
				  and (lease_expires_at is null or lease_expires_at < ?)
				""".trimIndent(), owner, Timestamp.from(now.plus(duration)), Timestamp.from(now), checkpointKey, Timestamp.from(now),
			) == 1
		}

	private fun releaseLease(checkpointKey: String, owner: String) {
		transactions.executeRequiresNew {
			sql.update(
				"""update signal_activity_chat_backfill_checkpoints
				   set lease_owner = null, lease_expires_at = null, updated_at = now()
				 where checkpoint_key = ? and lease_owner = ?""".trimIndent(), checkpointKey, owner,
			)
		}
	}

	private fun markFailed(checkpointKey: String, owner: String) {
		transactions.executeRequiresNew {
			sql.update(
				"""update signal_activity_chat_backfill_checkpoints set status = 'FAILED', updated_at = now()
				   where checkpoint_key = ? and lease_owner = ?""".trimIndent(), checkpointKey, owner,
			)
		}
	}

	private fun nextIncompletePhase(checkpointKey: String): BackfillPhase? =
		PHASES.firstOrNull { readCheckpoint(phaseKey(checkpointKey, it))?.status != COMPLETED }

	private fun processBatch(
		checkpointKey: String,
		phase: BackfillPhase,
		owner: String,
		batchSize: Int,
		leaseDuration: Duration,
	) = transactions.executeRequiresNew {
		val root = requireNotNull(readCheckpoint(checkpointKey, true))
		verifyLease(root, owner)
		val checkpoint = requireNotNull(readCheckpoint(phaseKey(checkpointKey, phase), true))
		if (checkpoint.status == COMPLETED) return@executeRequiresNew
		val rows = when (phase) {
			BackfillPhase.RUNS -> loadRuns(root.watermark, checkpoint.lastProcessedId, batchSize)
			BackfillPhase.OPPORTUNITIES -> loadOpportunities(root.watermark, checkpoint.lastProcessedId, batchSize)
			BackfillPhase.SIGNALS -> loadSignals(root.watermark, checkpoint.lastProcessedId, batchSize)
		}
		val inserted = rows.count { reconcile(it) }.toLong()
		val attempted = rows.size.toLong()
		val now = Instant.now()
		sql.update(
			"""
			update signal_activity_chat_backfill_checkpoints
			set last_processed_id = ?, status = ?, attempted_count = attempted_count + ?,
			    inserted_count = inserted_count + ?, skipped_count = skipped_count + ?,
			    reconciled_count = reconciled_count + ?, updated_at = ?
			where checkpoint_key = ?
			""".trimIndent(),
			rows.lastOrNull()?.id ?: checkpoint.lastProcessedId,
			if (rows.size < batchSize) COMPLETED else RUNNING,
			attempted, inserted, attempted - inserted, inserted, Timestamp.from(now), phaseKey(checkpointKey, phase),
		)
		sql.update(
			"""update signal_activity_chat_backfill_checkpoints set lease_expires_at = ?, updated_at = ?
				where checkpoint_key = ? and lease_owner = ?""".trimIndent(),
			Timestamp.from(now.plus(leaseDuration)), Timestamp.from(now), checkpointKey, owner,
		)
	}

	private fun finalizeRun(checkpointKey: String, owner: String) = transactions.executeRequiresNew {
		val root = requireNotNull(readCheckpoint(checkpointKey, true))
		verifyLease(root, owner)
		val lag = computeLag(root.watermark)
		sql.update(
			"""update signal_activity_chat_backfill_checkpoints set status = ?, lag_count = ?, updated_at = now()
				where checkpoint_key = ? and lease_owner = ?""".trimIndent(),
			if (lag.totalLag == 0L) COMPLETED else AUDIT_REQUIRED, lag.totalLag, checkpointKey, owner,
		)
	}

	fun computeLag(watermark: Instant): LagReport {
		fun count(query: String): Long = sql.queryForObject(query, Long::class.java, Timestamp.from(watermark)) ?: 0L
		val runs = count("""select count(*) from agent_runs r where r.created_at <= ? and r.origin = 'CHAT'
			and r.work_session_id is not null and not exists (select 1 from chat_response_versions v where v.agent_run_id = r.id)""")
		val opportunities = count("""select count(*) from autonomy_opportunities o where o.created_at <= ?
			and not exists (select 1 from legacy_activity_provenance p where p.opportunity_id = o.id)""")
		val signals = count("""select count(*) from autonomy_signals s where s.received_at <= ?
			and not exists (select 1 from signal_evaluations e where e.signal_id = s.id)""")
		return LagReport(runs, opportunities, signals, runs + opportunities + signals)
	}

	private fun reconcile(row: HistoricalRow): Boolean = when (row) {
		is HistoricalRun -> reconcileRun(row)
		is HistoricalOpportunity -> reconcileOpportunity(row)
		is HistoricalSignal -> reconcileSignal(row)
	}

	private fun reconcileRun(run: HistoricalRun): Boolean {
		sql.queryForObject(
			"select id from work_sessions where workspace_id = ? and id = ? for update",
			UUID::class.java,
			run.workspaceId,
			run.workSessionId,
		)
		if (sql.queryForObject("select 1 from chat_response_versions where workspace_id = ? and agent_run_id = ?", Int::class.java, run.workspaceId, run.id) != null) return false
		val nextTurnIndex = sql.queryForObject(
			"select coalesce(max(turn_index) + 1, 0) from chat_turns where workspace_id = ? and work_session_id = ?",
			Int::class.java, run.workspaceId, run.workSessionId,
		) ?: 0
		val turnId = uuidGenerator.next()
		sql.update(
			"""insert into chat_turns (id, workspace_id, work_session_id, turn_index, user_message, created_by_user_id, created_at, updated_at)
				values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (workspace_id, work_session_id, turn_index) do nothing""".trimIndent(),
			turnId, run.workspaceId, run.workSessionId, nextTurnIndex, run.instruction.ifBlank { "Legacy Turn" }, run.userId,
			Timestamp.from(run.createdAt), Timestamp.from(run.updatedAt),
		)
		val resolvedTurnId = sql.queryForObject(
			"select id from chat_turns where workspace_id = ? and work_session_id = ? and turn_index = ?",
			UUID::class.java, run.workspaceId, run.workSessionId, nextTurnIndex,
		) ?: turnId
		val inserted = sql.update(
			"""insert into chat_response_versions
				(id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, lineage_parent_version_id, is_active, created_at, updated_at)
				values (?, ?, ?, 0, ?, ?, null, ?, ?, ?) on conflict (workspace_id, agent_run_id) do nothing""".trimIndent(),
			uuidGenerator.next(), run.workspaceId, resolvedTurnId, run.id, run.userId,
			run.status in ACTIVE_RUN_STATUSES, Timestamp.from(run.createdAt), Timestamp.from(run.updatedAt),
		)
		if (inserted == 0) return false
		val settings = objectMapper.writeValueAsString(mapOf(
			"promptVersion" to run.promptVersion,
			"toolPolicyVersion" to run.toolPolicyVersion,
		))
		sql.update(
			"""insert into chat_execution_envelopes
				(id, workspace_id, agent_run_id, fingerprint_version, envelope_fingerprint, generation_settings, source_snapshot_id, created_at)
				values (?, ?, ?, 1, ?, ?::jsonb, null, ?) on conflict (workspace_id, agent_run_id) do nothing""".trimIndent(),
			uuidGenerator.next(), run.workspaceId, run.id, run.requestFingerprint, settings, Timestamp.from(run.createdAt),
		)
		return true
	}

	private fun reconcileOpportunity(row: HistoricalOpportunity): Boolean {
		val disposition = when {
			row.dismissed || row.disposition == "EXCLUDED" -> "EXCLUDED"
			row.agentRunId != null -> "ADMITTED"
			else -> "NO_GENERATION"
		}
		return sql.update(
			"""insert into legacy_activity_provenance
				(id, workspace_id, source_scope_id, opportunity_id, goal_id, task_id, agent_run_id, chat_id, title, disposition, reason,
				 dismissed, missing_facts, last_error_code, has_exact_signal_link, uncertainty_label, semantic_time, created_at)
				values (?, ?, ?, ?, ?, null, ?, null, ?, ?, ?, ?, ?::jsonb, ?, false, 'MIGRATED_HISTORICAL_RECORD', ?, ?)
				on conflict (workspace_id, id) do nothing""".trimIndent(),
			UUID.nameUUIDFromBytes("legacy_provenance:${row.workspaceId}:${row.id}".toByteArray()),
			row.workspaceId, row.sourceScopeId, row.id, row.goalId, row.agentRunId, row.title, disposition, row.reason,
			row.dismissed, row.missingFacts, row.lastErrorCode, Timestamp.from(row.updatedAt), Timestamp.from(row.createdAt),
		) == 1
	}

	private fun reconcileSignal(row: HistoricalSignal): Boolean = sql.update(
		"""insert into signal_evaluations
			(id, workspace_id, signal_id, source_namespace_id, source_scope_id, input_fingerprint, outcome, reason, semantic_time, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, 'NO_GENERATION', 'Historical signal backfill', ?, ?, ?)
			on conflict (workspace_id, signal_id) do nothing""".trimIndent(),
		uuidGenerator.next(), row.workspaceId, row.id, row.namespaceId, row.scopeId, row.deliveryKey,
		Timestamp.from(row.receivedAt), Timestamp.from(row.receivedAt), Timestamp.from(row.receivedAt),
	) == 1

	private fun loadRuns(watermark: Instant, lastId: UUID?, limit: Int): List<HistoricalRun> = sql.query(
		"""select id, workspace_id, work_session_id, created_by_user_id, instruction_snapshot, prompt_version, tool_policy_version,
			request_fingerprint, status, created_at, updated_at from agent_runs r
			where r.created_at <= ? and r.origin = 'CHAT' and r.work_session_id is not null
			and not exists (select 1 from chat_response_versions v where v.agent_run_id = r.id)
			and (?::uuid is null or r.id > ?::uuid) order by r.id asc limit ?""".trimIndent(),
		{ row, _ -> HistoricalRun(
			requireNotNull(row.getObject("id", UUID::class.java)), requireNotNull(row.getObject("workspace_id", UUID::class.java)),
			requireNotNull(row.getObject("work_session_id", UUID::class.java)), row.getObject("created_by_user_id", UUID::class.java),
			row.getString("instruction_snapshot") ?: "", row.getString("prompt_version") ?: "v1", row.getString("tool_policy_version") ?: "v1",
			row.getString("request_fingerprint") ?: "legacy", row.getString("status") ?: "SUCCEEDED",
			requireNotNull(row.getTimestamp("created_at")).toInstant(), requireNotNull(row.getTimestamp("updated_at") ?: row.getTimestamp("created_at")).toInstant(),
		) }, Timestamp.from(watermark), lastId, lastId, limit,
	)

	private fun loadOpportunities(watermark: Instant, lastId: UUID?, limit: Int): List<HistoricalOpportunity> = sql.query(
		"""select distinct on (o.id) o.id, o.workspace_id, o.source_scope_id, o.title, o.disposition, o.reason, o.missing_facts,
			o.last_error_code, o.dismissed, o.created_at, o.updated_at, g.id as goal_id, g.agent_run_id from autonomy_opportunities o
			left join autonomy_goals g on g.workspace_id = o.workspace_id and g.opportunity_id = o.id
			where o.created_at <= ? and (?::uuid is null or o.id > ?::uuid)
			and not exists (select 1 from legacy_activity_provenance p where p.opportunity_id = o.id)
			order by o.id, (g.agent_run_id is not null) desc, g.created_at desc nulls last limit ?""".trimIndent(),
		{ row, _ -> HistoricalOpportunity(
			requireNotNull(row.getObject("id", UUID::class.java)), requireNotNull(row.getObject("workspace_id", UUID::class.java)),
			requireNotNull(row.getObject("source_scope_id", UUID::class.java)), row.getString("title") ?: "Legacy Opportunity",
			row.getString("disposition") ?: "AWAITING_EVIDENCE", row.getString("reason") ?: "Historical opportunity",
			row.getString("missing_facts") ?: "[]", row.getString("last_error_code"), row.getBoolean("dismissed"),
			requireNotNull(row.getTimestamp("created_at")).toInstant(), requireNotNull(row.getTimestamp("updated_at") ?: row.getTimestamp("created_at")).toInstant(),
			row.getObject("goal_id", UUID::class.java), row.getObject("agent_run_id", UUID::class.java),
		) }, Timestamp.from(watermark), lastId, lastId, limit,
	)

	private fun loadSignals(watermark: Instant, lastId: UUID?, limit: Int): List<HistoricalSignal> = sql.query(
		"""select id, workspace_id, source_namespace_id, source_scope_id, delivery_key, received_at from autonomy_signals s
			where s.received_at <= ? and (?::uuid is null or s.id > ?::uuid)
			and not exists (select 1 from signal_evaluations e where e.signal_id = s.id) order by s.id asc limit ?""".trimIndent(),
		{ row, _ -> HistoricalSignal(
			requireNotNull(row.getObject("id", UUID::class.java)), requireNotNull(row.getObject("workspace_id", UUID::class.java)),
			requireNotNull(row.getObject("source_namespace_id", UUID::class.java)), requireNotNull(row.getObject("source_scope_id", UUID::class.java)),
			row.getString("delivery_key") ?: "", requireNotNull(row.getTimestamp("received_at")).toInstant(),
		) }, Timestamp.from(watermark), lastId, lastId, limit,
	)

	private fun report(checkpointKey: String, leaseAcquired: Boolean): BackfillReport {
		val root = readCheckpoint(checkpointKey) ?: return BackfillReport(checkpointKey, null, 0, 0, 0, null, NOT_STARTED, leaseAcquired, emptyList())
		val phases = PHASES.mapNotNull { phase -> readCheckpoint(phaseKey(checkpointKey, phase))?.let { checkpoint ->
			PhaseReport(phase.key, checkpoint.status, checkpoint.attemptedCount, checkpoint.insertedCount, checkpoint.skippedCount, checkpoint.lastProcessedId)
		} }
		val lag = if (root.status in setOf(COMPLETED, AUDIT_REQUIRED)) computeLag(root.watermark) else null
		return BackfillReport(checkpointKey, root.watermark, phases.sumOf { it.attemptedCount }, phases.sumOf { it.insertedCount },
			phases.sumOf { it.skippedCount }, lag, root.status, leaseAcquired, phases)
	}

	private fun readCheckpoint(checkpointKey: String, forUpdate: Boolean = false): BackfillCheckpoint? = sql.queryForObject(
		"""select checkpoint_key, high_water_mark, last_processed_id, status, attempted_count, inserted_count, skipped_count,
			lease_owner, lease_expires_at from signal_activity_chat_backfill_checkpoints where checkpoint_key = ? ${if (forUpdate) "for update" else ""}""",
		{ row, _ -> BackfillCheckpoint(
			requireNotNull(row.getString("checkpoint_key")), requireNotNull(row.getTimestamp("high_water_mark")).toInstant(),
			row.getObject("last_processed_id", UUID::class.java), requireNotNull(row.getString("status")), row.getLong("attempted_count"),
			row.getLong("inserted_count"), row.getLong("skipped_count"), row.getString("lease_owner"), row.getTimestamp("lease_expires_at")?.toInstant(),
		) }, checkpointKey,
	)

	private fun verifyLease(checkpoint: BackfillCheckpoint, owner: String) {
		require(checkpoint.leaseOwner == owner && checkpoint.leaseExpiresAt?.isAfter(Instant.now()) == true) { "Signal Activity Chat backfill lease was lost" }
	}

	private fun validateRequest(checkpointKey: String, batchSize: Int, maxBatches: Int, leaseDuration: Duration) {
		validateCheckpointKey(checkpointKey)
		require(batchSize in 1..MAX_BATCH_SIZE) { "Backfill batch size must be between 1 and $MAX_BATCH_SIZE" }
		require(maxBatches in 1..MAX_BATCHES_PER_RUN) { "Backfill max batches must be between 1 and $MAX_BATCHES_PER_RUN" }
		require(!leaseDuration.isZero && !leaseDuration.isNegative) { "Backfill lease duration must be positive" }
	}

	private fun validateCheckpointKey(checkpointKey: String) {
		require(CHECKPOINT_KEY.matches(checkpointKey)) { "Backfill checkpoint key is invalid" }
		require(PHASES.all { phaseKey(checkpointKey, it).length <= 64 }) { "Backfill checkpoint key is too long" }
	}

	private fun phaseKey(checkpointKey: String, phase: BackfillPhase) = "$checkpointKey.${phase.key}"
	private enum class BackfillPhase(val key: String) { RUNS("runs"), OPPORTUNITIES("opportunities"), SIGNALS("signals") }
	private data class BackfillCheckpoint(
		val key: String, val watermark: Instant, val lastProcessedId: UUID?, val status: String, val attemptedCount: Long,
		val insertedCount: Long, val skippedCount: Long, val leaseOwner: String?, val leaseExpiresAt: Instant?,
	)
	private sealed interface HistoricalRow { val id: UUID }
	private data class HistoricalRun(override val id: UUID, val workspaceId: UUID, val workSessionId: UUID, val userId: UUID?, val instruction: String,
		val promptVersion: String, val toolPolicyVersion: String, val requestFingerprint: String, val status: String, val createdAt: Instant, val updatedAt: Instant) : HistoricalRow
	private data class HistoricalOpportunity(override val id: UUID, val workspaceId: UUID, val sourceScopeId: UUID, val title: String, val disposition: String,
		val reason: String, val missingFacts: String, val lastErrorCode: String?, val dismissed: Boolean, val createdAt: Instant, val updatedAt: Instant,
		val goalId: UUID?, val agentRunId: UUID?) : HistoricalRow
	private data class HistoricalSignal(override val id: UUID, val workspaceId: UUID, val namespaceId: UUID, val scopeId: UUID,
		val deliveryKey: String, val receivedAt: Instant) : HistoricalRow

	private companion object {
		const val DEFAULT_CHECKPOINT_KEY = "signal_activity_chat_backfill_v2"
		const val DEFAULT_BATCH_SIZE = 100
		const val DEFAULT_MAX_BATCHES = 100
		const val MAX_BATCH_SIZE = 500
		const val MAX_BATCHES_PER_RUN = 10_000
		val DEFAULT_LEASE_DURATION: Duration = Duration.ofMinutes(2)
		val CHECKPOINT_KEY = Regex("^[A-Za-z0-9._-]{1,48}$")
		val PHASES = BackfillPhase.entries
		val ACTIVE_RUN_STATUSES = setOf("QUEUED", "RUNNING")
		const val NOT_STARTED = "NOT_STARTED"
		const val RUNNING = "RUNNING"
		const val COMPLETED = "COMPLETED"
		const val AUDIT_REQUIRED = "AUDIT_REQUIRED"
	}
}
