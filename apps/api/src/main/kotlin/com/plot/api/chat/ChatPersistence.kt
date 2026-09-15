package com.plot.api.chat

import com.plot.api.agent.AgentRunRecord
import com.plot.api.agent.agentRunMapper
import com.plot.api.agent.selectAgentRunSql
import com.plot.api.persistence.SqlExecutor
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

@Repository
class ChatPersistence(private val sqlExecutor: SqlExecutor) {
	fun sessionExists(workspaceId: UUID, sessionId: UUID): Boolean = sqlExecutor.queryForObject(
		"select exists(select 1 from work_sessions where workspace_id = ? and id = ?)",
		Boolean::class.java,
		workspaceId,
		sessionId,
	) ?: false
	fun listSessionAgentRuns(workspaceId: UUID, sessionId: UUID): List<AgentRunRecord> = sqlExecutor.query(
		selectAgentRunSql + " where a.workspace_id = ? and a.work_session_id = ? order by a.created_at, a.id",
		agentRunMapper,
		workspaceId,
		sessionId,
	)
	fun findChatAgentRunByIdempotencyKey(
		workspaceId: UUID,
		idempotencyKey: String,
		forUpdate: Boolean = false,
	): AgentRunRecord? = sqlExecutor.query(
		selectAgentRunSql + " where a.workspace_id = ? and a.origin = 'CHAT' and a.idempotency_key = ?" +
			if (forUpdate) " for update" else "",
		agentRunMapper,
		workspaceId,
		idempotencyKey,
	).singleOrNull()
	fun listTurns(workspaceId: UUID, sessionId: UUID): List<ChatTurnRow> = sqlExecutor.query(
		"""
		select id, workspace_id, work_session_id, turn_index, user_message, created_by_user_id, created_at, updated_at
		from chat_turns
		where workspace_id = ? and work_session_id = ?
		order by turn_index asc
		""".trimIndent(),
		{ rs, _ ->
			ChatTurnRow(
				id = requireNotNull(rs.getObject("id", UUID::class.java)),
				workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
				workSessionId = requireNotNull(rs.getObject("work_session_id", UUID::class.java)),
				turnIndex = rs.getInt("turn_index"),
				userMessage = requireNotNull(rs.getString("user_message")),
				createdByUserId = rs.getObject("created_by_user_id", UUID::class.java),
				createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
				updatedAt = requireNotNull(rs.getTimestamp("updated_at")).toInstant(),
			)
		},
		workspaceId,
		sessionId,
	)

	fun listResponseVersionsForTurn(workspaceId: UUID, turnId: UUID): List<ChatResponseVersionRow> = sqlExecutor.query(
		"""
		select id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, lineage_parent_version_id, created_at, updated_at
		from chat_response_versions
		where workspace_id = ? and turn_id = ?
		order by version_index asc
		""".trimIndent(),
		{ rs, _ ->
			ChatResponseVersionRow(
				id = requireNotNull(rs.getObject("id", UUID::class.java)),
				workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
				turnId = requireNotNull(rs.getObject("turn_id", UUID::class.java)),
				versionIndex = rs.getInt("version_index"),
				agentRunId = requireNotNull(rs.getObject("agent_run_id", UUID::class.java)),
				initiatorUserId = rs.getObject("initiator_user_id", UUID::class.java),
				lineageParentVersionId = rs.getObject("lineage_parent_version_id", UUID::class.java),
				createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
				updatedAt = requireNotNull(rs.getTimestamp("updated_at")).toInstant(),
			)
		},
		workspaceId,
		turnId,
	)

	fun findResponseVersion(workspaceId: UUID, versionId: UUID): ChatResponseVersionRow? = sqlExecutor.query(
		"""
		select id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, lineage_parent_version_id, created_at, updated_at
		from chat_response_versions
		where workspace_id = ? and id = ?
		""".trimIndent(),
		{ rs, _ ->
			ChatResponseVersionRow(
				id = requireNotNull(rs.getObject("id", UUID::class.java)),
				workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
				turnId = requireNotNull(rs.getObject("turn_id", UUID::class.java)),
				versionIndex = rs.getInt("version_index"),
				agentRunId = requireNotNull(rs.getObject("agent_run_id", UUID::class.java)),
				initiatorUserId = rs.getObject("initiator_user_id", UUID::class.java),
				lineageParentVersionId = rs.getObject("lineage_parent_version_id", UUID::class.java),
				createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
				updatedAt = requireNotNull(rs.getTimestamp("updated_at")).toInstant(),
			)
		},
		workspaceId,
		versionId,
	).firstOrNull()

	fun findResponseVersionByRunId(workspaceId: UUID, agentRunId: UUID): ChatResponseVersionRow? = sqlExecutor.query(
		"""
		select id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, lineage_parent_version_id, created_at, updated_at
		from chat_response_versions
		where workspace_id = ? and agent_run_id = ?
		""".trimIndent(),
		{ rs, _ ->
			ChatResponseVersionRow(
				id = requireNotNull(rs.getObject("id", UUID::class.java)),
				workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
				turnId = requireNotNull(rs.getObject("turn_id", UUID::class.java)),
				versionIndex = rs.getInt("version_index"),
				agentRunId = requireNotNull(rs.getObject("agent_run_id", UUID::class.java)),
				initiatorUserId = rs.getObject("initiator_user_id", UUID::class.java),
				lineageParentVersionId = rs.getObject("lineage_parent_version_id", UUID::class.java),
				createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
				updatedAt = requireNotNull(rs.getTimestamp("updated_at")).toInstant(),
			)
		},
		workspaceId,
		agentRunId,
	).firstOrNull()

	fun findTurn(workspaceId: UUID, turnId: UUID): ChatTurnRow? = sqlExecutor.query(
		"""
		select id, workspace_id, work_session_id, turn_index, user_message, created_by_user_id, created_at, updated_at
		from chat_turns
		where workspace_id = ? and id = ?
		""".trimIndent(),
		{ rs, _ ->
			ChatTurnRow(
				id = requireNotNull(rs.getObject("id", UUID::class.java)),
				workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
				workSessionId = requireNotNull(rs.getObject("work_session_id", UUID::class.java)),
				turnIndex = rs.getInt("turn_index"),
				userMessage = requireNotNull(rs.getString("user_message")),
				createdByUserId = rs.getObject("created_by_user_id", UUID::class.java),
				createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
				updatedAt = requireNotNull(rs.getTimestamp("updated_at")).toInstant(),
			)
		},
		workspaceId,
		turnId,
	).firstOrNull()


	fun deactivateResponse(workspaceId: UUID, agentRunId: UUID) {
		sqlExecutor.update(
			"update chat_response_versions set is_active = false, updated_at = now() where workspace_id = ? and agent_run_id = ?",
			workspaceId, agentRunId,
		)
	}
}

data class ChatTurnRow(
	val id: UUID,
	val workspaceId: UUID,
	val workSessionId: UUID,
	val turnIndex: Int,
	val userMessage: String,
	val createdByUserId: UUID?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

data class ChatResponseVersionRow(
	val id: UUID,
	val workspaceId: UUID,
	val turnId: UUID,
	val versionIndex: Int,
	val agentRunId: UUID,
	val initiatorUserId: UUID?,
	val lineageParentVersionId: UUID?,
	val createdAt: Instant,
	val updatedAt: Instant,
)
