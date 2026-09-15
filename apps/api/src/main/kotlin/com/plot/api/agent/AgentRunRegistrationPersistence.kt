package com.plot.api.agent

import com.plot.api.common.UuidGenerator
import com.plot.api.content.ContentType
import com.plot.api.content.ContentSourceSnapshotInput
import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

/** Writes accepted execution data. Callers own validation, locks, transactions and dispatch. */
@Repository
class AgentRunRegistrationPersistence(
	private val sqlExecutor: SqlExecutor,
	private val uuidGenerator: UuidGenerator,
	private val queries: AgentRunQueryPersistence,
) {
	fun insertRequired(run: NewAgentRun, now: Instant) {
		insert(run, now, ignoreChatIdempotencyConflict = false)
	}

	fun insertChatIfAbsent(run: NewAgentRun, now: Instant): Boolean =
		insert(run, now, ignoreChatIdempotencyConflict = true) == 1

	private fun insert(run: NewAgentRun, now: Instant, ignoreChatIdempotencyConflict: Boolean): Int {
		require(!ignoreChatIdempotencyConflict || run.origin == AgentRunOrigin.CHAT)
		val conflictClause = if (ignoreChatIdempotencyConflict) {
			"on conflict (workspace_id, idempotency_key) where origin = 'CHAT' do nothing"
		} else ""
		return sqlExecutor.update(
			"""
			insert into agent_runs (
			  id, workspace_id, routine_execution_id, routine_id, work_session_id, created_by_user_id,
			  origin, idempotency_key, request_fingerprint,
			  instruction_snapshot, skills_snapshot, prompt_version, tool_policy_version, budget_snapshot, content_type,
			  content_profile_revision_id, content_brief_snapshot, source_snapshot_id,
			  status, current_step, attempt_count, max_attempts, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, 'QUEUED', 0, 0, ?, ?, ?)
			$conflictClause
			""".trimIndent(),
			run.id, run.workspaceId, run.routineExecutionId, run.routineId, run.workSessionId, run.createdByUserId,
			run.origin.name, run.idempotencyKey, run.requestFingerprint,
			run.instructionSnapshot, skillsSnapshot(run), run.promptVersion, run.toolPolicyVersion, run.budgetSnapshotJson, run.contentType.name,
			run.contentProfileRevisionId, run.contentBriefSnapshotJson, run.sourceSnapshotId,
			run.maxAttempts, Timestamp.from(now), Timestamp.from(now),
		)
	}

	private fun skillsSnapshot(run: NewAgentRun): String = run.skillsSnapshotJson ?: run.routineId?.let { routineId ->
		sqlExecutor.query("select skills_snapshot::text from routines where workspace_id = ? and id = ?", run.workspaceId, routineId)
			.firstOrNull()?.getString("skills_snapshot")
	} ?: "[]"

	fun insertSource(
		workspaceId: UUID,
		agentRunId: UUID,
		sourceScopeId: UUID,
		displayName: String?,
		role: AgentRunSourceRole,
		orderIndex: Int,
		capturedStatus: String,
		capturedStatusChangedAt: Instant,
		now: Instant,
	) {
		sqlExecutor.update(
			"""
			insert into agent_run_sources (
			  id, workspace_id, agent_run_id, source_scope_id, source_display_name, source_role, order_index,
			  captured_status, captured_status_changed_at, captured_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			uuidGenerator.next(), workspaceId, agentRunId, sourceScopeId, displayName, role.name, orderIndex,
			capturedStatus, Timestamp.from(capturedStatusChangedAt), Timestamp.from(now),
		)
	}

	fun insertInput(workspaceId: UUID, agentRunId: UUID, input: AgentRunInputRequest): UUID {
		val id = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into agent_run_inputs (
			  id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
			  source_provider, source_kind, source_label, input_kind, order_index, activity_sequence,
			  snapshot_title, snapshot_body, snapshot_excerpt, original_url,
			  source_created_at, source_updated_at, content_hash, captured_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			id, workspaceId, agentRunId, input.routineId, input.sourceScopeId, input.writingBlockId,
			input.sourceProvider, input.sourceKind, input.sourceLabel, input.inputKind.name, input.orderIndex, input.activitySequence,
			input.snapshotTitle, input.snapshotBody, input.snapshotExcerpt, input.originalUrl,
			input.sourceCreatedAt?.let(Timestamp::from), input.sourceUpdatedAt?.let(Timestamp::from), input.contentHash,
			Timestamp.from(input.capturedAt),
		)
		return id
	}

	fun insertSnapshotInput(workspaceId: UUID, runId: UUID, input: ContentSourceSnapshotInput, index: Int, now: Instant) {
		sqlExecutor.update(
			"""
			insert into agent_run_inputs (
			  id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
			  source_provider, source_kind, source_label,
			  input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
			  snapshot_excerpt, original_url, source_created_at, source_updated_at,
			  content_hash, captured_at
			) values (?, ?, ?, null, ?, ?, ?, ?, ?, 'SEED', ?, null, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			uuidGenerator.next(),
			workspaceId,
			runId,
			input.sourceScopeId,
			input.writingBlockId,
			input.sourceProvider,
			input.sourceKind,
			input.sourceLabel,
			index,
			input.snapshotTitle,
			input.snapshotBody,
			input.snapshotExcerpt,
			input.originalUrl,
			input.sourceCreatedAt?.let(Timestamp::from),
			input.sourceUpdatedAt?.let(Timestamp::from),
			input.contentHash,
			Timestamp.from(now),
		)
	}

	fun copySourcesAndInputs(workspaceId: UUID, targetRunId: UUID, newRunId: UUID, now: Instant) {
		for (source in queries.listAgentRunSources(workspaceId, targetRunId)) {
			insertSource(
				workspaceId, newRunId, source.sourceScopeId, source.displayName, source.role, source.orderIndex,
				source.capturedStatus, source.capturedStatusChangedAt, now,
			)
		}
		for (input in queries.listAgentRunInputs(workspaceId, targetRunId)) {
			insertInput(workspaceId, newRunId, AgentRunInputRequest(
				routineId = input.routineId,
				sourceScopeId = input.sourceScopeId,
				writingBlockId = input.writingBlockId,
				sourceProvider = input.sourceProvider,
				sourceKind = input.sourceKind,
				sourceLabel = input.sourceLabel,
				inputKind = input.inputKind,
				orderIndex = input.orderIndex,
				activitySequence = input.activitySequence,
				snapshotTitle = input.snapshotTitle,
				snapshotBody = input.snapshotBody,
				snapshotExcerpt = input.snapshotExcerpt,
				originalUrl = input.originalUrl,
				sourceCreatedAt = input.sourceCreatedAt,
				sourceUpdatedAt = input.sourceUpdatedAt,
				contentHash = input.contentHash,
				capturedAt = now,
			))
		}
	}
}

data class NewAgentRun(
	val id: UUID,
	val workspaceId: UUID,
	val workSessionId: UUID,
	val createdByUserId: UUID,
	val origin: AgentRunOrigin,
	val idempotencyKey: String,
	val requestFingerprint: String,
	val instructionSnapshot: String,
	val skillsSnapshotJson: String? = null,
	val promptVersion: String,
	val toolPolicyVersion: String,
	val budgetSnapshotJson: String,
	val contentType: ContentType,
	val contentProfileRevisionId: UUID?,
	val contentBriefSnapshotJson: String?,
	val maxAttempts: Int,
	val routineExecutionId: UUID? = null,
	val routineId: UUID? = null,
	val sourceSnapshotId: UUID? = null,
)
