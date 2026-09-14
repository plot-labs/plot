package com.plot.api.routine

import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.JooqSqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class ChatCompatibilityWriter(
	private val sqlExecutor: JooqSqlExecutor,
	private val uuidGenerator: UuidGenerator,
) {
	fun recordDirectChatRun(
		workspaceId: UUID,
		userId: UUID,
		chatId: UUID,
		runId: UUID,
		instruction: String,
		fingerprint: String,
		settingsJson: String,
		sourceSnapshotId: UUID? = null,
		now: Instant = Instant.now(),
	) {
		val existingVersion = sqlExecutor.queryForObject(
			"select id from chat_response_versions where workspace_id = ? and agent_run_id = ?",
			UUID::class.java,
			workspaceId,
			runId,
		)
		if (existingVersion != null) return

		val nextTurnIndex = sqlExecutor.queryForObject(
			"select coalesce(max(turn_index) + 1, 0) from chat_turns where workspace_id = ? and work_session_id = ?",
			Int::class.java,
			workspaceId,
			chatId,
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
			workspaceId,
			chatId,
			nextTurnIndex,
			instruction.trim(),
			userId,
			Timestamp.from(now),
			Timestamp.from(now),
		)

		val resolvedTurnId = sqlExecutor.queryForObject(
			"select id from chat_turns where workspace_id = ? and work_session_id = ? and turn_index = ?",
			UUID::class.java,
			workspaceId,
			chatId,
			nextTurnIndex,
		) ?: turnId

		val versionId = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into chat_response_versions (
				id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id,
				lineage_parent_version_id, is_active, created_at, updated_at
			) values (?, ?, ?, 0, ?, ?, null, true, ?, ?)
			on conflict (workspace_id, agent_run_id) do nothing
			""".trimIndent(),
			versionId,
			workspaceId,
			resolvedTurnId,
			runId,
			userId,
			Timestamp.from(now),
			Timestamp.from(now),
		)

		val envelopeId = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into chat_execution_envelopes (
				id, workspace_id, agent_run_id, fingerprint_version, envelope_fingerprint,
				generation_settings, source_snapshot_id, created_at
			) values (?, ?, ?, 1, ?, ?::jsonb, ?, ?)
			on conflict (workspace_id, agent_run_id) do nothing
			""".trimIndent(),
			envelopeId,
			workspaceId,
			runId,
			fingerprint,
			settingsJson,
			sourceSnapshotId,
			Timestamp.from(now),
		)
	}

	fun recordRoutineRun(
		workspaceId: UUID,
		userId: UUID,
		workSessionId: UUID,
		runId: UUID,
		instruction: String,
		fingerprint: String,
		settingsJson: String,
		now: Instant = Instant.now(),
	) {
		val existingVersion = sqlExecutor.queryForObject(
			"select id from chat_response_versions where workspace_id = ? and agent_run_id = ?",
			UUID::class.java,
			workspaceId,
			runId,
		)
		if (existingVersion != null) return

		val turnId = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into chat_turns (
				id, workspace_id, work_session_id, turn_index, user_message, created_by_user_id, created_at, updated_at
			) values (?, ?, ?, 0, ?, ?, ?, ?)
			on conflict (workspace_id, work_session_id, turn_index) do nothing
			""".trimIndent(),
			turnId,
			workspaceId,
			workSessionId,
			instruction.trim(),
			userId,
			Timestamp.from(now),
			Timestamp.from(now),
		)

		val resolvedTurnId = sqlExecutor.queryForObject(
			"select id from chat_turns where workspace_id = ? and work_session_id = ? and turn_index = 0",
			UUID::class.java,
			workspaceId,
			workSessionId,
		) ?: turnId

		val versionId = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into chat_response_versions (
				id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id,
				lineage_parent_version_id, is_active, created_at, updated_at
			) values (?, ?, ?, 0, ?, ?, null, true, ?, ?)
			on conflict (workspace_id, agent_run_id) do nothing
			""".trimIndent(),
			versionId,
			workspaceId,
			resolvedTurnId,
			runId,
			userId,
			Timestamp.from(now),
			Timestamp.from(now),
		)

		val envelopeId = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into chat_execution_envelopes (
				id, workspace_id, agent_run_id, fingerprint_version, envelope_fingerprint,
				generation_settings, source_snapshot_id, created_at
			) values (?, ?, ?, 1, ?, ?::jsonb, null, ?)
			on conflict (workspace_id, agent_run_id) do nothing
			""".trimIndent(),
			envelopeId,
			workspaceId,
			runId,
			fingerprint,
			settingsJson,
			Timestamp.from(now),
		)
	}

	fun recordTranscriptEntry(
		workspaceId: UUID,
		agentRunId: UUID,
		callIndex: Int,
		toolName: String,
		argumentsJson: String,
		resultJson: String,
		adoptedInputHash: String? = null,
		now: Instant = Instant.now(),
	) {
		val envelopeId = sqlExecutor.queryForObject(
			"select id from chat_execution_envelopes where workspace_id = ? and agent_run_id = ?",
			UUID::class.java,
			workspaceId,
			agentRunId,
		) ?: return

		val transcriptId = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into chat_execution_transcript_entries (
				id, workspace_id, envelope_id, call_index, tool_name, normalized_arguments,
				bounded_result, adopted_input_hash, created_at
			) values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
			on conflict (workspace_id, envelope_id, call_index) do update
			set bounded_result = excluded.bounded_result,
			    adopted_input_hash = coalesce(excluded.adopted_input_hash, chat_execution_transcript_entries.adopted_input_hash)
			""".trimIndent(),
			transcriptId,
			workspaceId,
			envelopeId,
			callIndex,
			toolName,
			argumentsJson,
			resultJson,
			adoptedInputHash,
			Timestamp.from(now),
		)
	}

	fun linkSourceSnapshot(workspaceId: UUID, agentRunId: UUID, sourceSnapshotId: UUID) {
		sqlExecutor.update(
			"update chat_execution_envelopes set source_snapshot_id = ? where workspace_id = ? and agent_run_id = ? and source_snapshot_id is null",
			sourceSnapshotId,
			workspaceId,
			agentRunId,
		)
	}
}
