package com.plot.api.chat

import com.plot.api.agent.AgentExecutionSnapshotPersistence
import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class ChatCompatibilityWriter(
	private val sqlExecutor: SqlExecutor,
	private val uuidGenerator: UuidGenerator,
	private val snapshots: AgentExecutionSnapshotPersistence,
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
		lockChatSession(workspaceId, chatId)
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

		snapshots.insertEnvelope(uuidGenerator.next(), workspaceId, runId, 1, fingerprint, settingsJson, sourceSnapshotId, now)
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
		lockChatSession(workspaceId, workSessionId)
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

		snapshots.insertEnvelope(uuidGenerator.next(), workspaceId, runId, 1, fingerprint, settingsJson, null, now)
	}

	private fun lockChatSession(workspaceId: UUID, chatId: UUID) {
		sqlExecutor.queryForObject(
			"select id from work_sessions where workspace_id = ? and id = ? for update",
			UUID::class.java,
			workspaceId,
			chatId,
		)
	}
}
