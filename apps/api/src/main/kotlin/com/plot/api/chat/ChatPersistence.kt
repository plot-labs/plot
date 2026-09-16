package com.plot.api.chat

import com.plot.api.agent.AgentRunRecord
import com.plot.api.agent.agentRunMapper
import com.plot.api.agent.selectAgentRunSql
import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

@Repository
class ChatPersistence(private val sqlExecutor: SqlExecutor) {
	fun sessionExists(workspaceId: UUID, sessionId: UUID): Boolean = sqlExecutor.queryForObject(
		"select exists(select 1 from work_sessions where workspace_id = ? and id = ? and session_kind <> 'AUTOMATION')",
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
	fun listResponseTextsByRunId(workspaceId: UUID, agentRunIds: List<UUID>): Map<UUID, String?> {
		if (agentRunIds.isEmpty()) return emptyMap()
		val placeholders = agentRunIds.joinToString(",") { "?" }
		return sqlExecutor.query(
			"""
			select agent_run_id, response_text
			from chat_response_versions
			where workspace_id = ? and agent_run_id in ($placeholders)
			""".trimIndent(),
			{ rs, _ -> requireNotNull(rs.getObject("agent_run_id", UUID::class.java)) to rs.getString("response_text") },
			workspaceId,
			*agentRunIds.toTypedArray(),
		).toMap()
	}
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
		select id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, lineage_parent_version_id, response_text, created_at, updated_at
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
				responseText = rs.getString("response_text"),
				createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
				updatedAt = requireNotNull(rs.getTimestamp("updated_at")).toInstant(),
			)
		},
		workspaceId,
		turnId,
	)

	fun findResponseVersion(workspaceId: UUID, versionId: UUID): ChatResponseVersionRow? = sqlExecutor.query(
		"""
		select id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, lineage_parent_version_id, response_text, created_at, updated_at
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
				responseText = rs.getString("response_text"),
				createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
				updatedAt = requireNotNull(rs.getTimestamp("updated_at")).toInstant(),
			)
		},
		workspaceId,
		versionId,
	).firstOrNull()

	fun findResponseVersionByRunId(workspaceId: UUID, agentRunId: UUID): ChatResponseVersionRow? = sqlExecutor.query(
		"""
		select id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, lineage_parent_version_id, response_text, created_at, updated_at
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
				responseText = rs.getString("response_text"),
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

	fun commitResponse(workspaceId: UUID, agentRunId: UUID, responseText: String, now: Instant) {
		check(sqlExecutor.update(
			"""
			update chat_response_versions
			set response_text = ?, updated_at = ?
			where workspace_id = ? and agent_run_id = ? and response_text is null
			""".trimIndent(),
			responseText,
			Timestamp.from(now),
			workspaceId,
			agentRunId,
		) == 1) { "Chat response version is unavailable" }
	}

	fun listConversation(workspaceId: UUID, sessionId: UUID, maxCharacters: Int): List<ChatConversationMessageRow> {
		require(maxCharacters > 0)
		val turns = sqlExecutor.query(
			"""
			with recent_turns as (
			  select id, turn_index, left(user_message, ?) as user_message
			  from chat_turns
			  where workspace_id = ? and work_session_id = ?
			  order by turn_index desc
			  limit 12
			)
			select turn.turn_index, turn.user_message,
			       left(coalesce(nullif(trim(response.response_text), ''), artifact.artifact_text), ?) as assistant_message
			from recent_turns turn
			left join lateral (
			  select version.response_text, version.agent_run_id
			  from chat_response_versions version
			  where version.workspace_id = ? and version.turn_id = turn.id
			  order by version.version_index desc
			  limit 1
			) response on true
			left join lateral (
			  select concat_ws(
			    E'\n\n',
			    'Created artifact: ' || coalesce(pack.title, variant.title, 'Untitled'),
			    nullif(string_agg(revision.body, E'\n' order by sentence.order_index), '')
			  ) as artifact_text
			  from artifact_runs artifact_run
			  join generation_runs generation
			    on generation.workspace_id = artifact_run.workspace_id
			   and generation.artifact_run_id = artifact_run.id
			  join content_packs pack
			    on pack.workspace_id = generation.workspace_id
			   and pack.generation_run_id = generation.id
			  join content_variants variant
			    on variant.workspace_id = pack.workspace_id
			   and variant.content_pack_id = pack.id
			   and variant.variant_index = 0
			  left join content_variant_sentences sentence
			    on sentence.workspace_id = variant.workspace_id
			   and sentence.content_variant_id = variant.id
			  left join content_variant_sentence_revisions revision
			    on revision.workspace_id = sentence.workspace_id
			   and revision.sentence_id = sentence.id
			   and revision.is_current
			  where artifact_run.workspace_id = ? and artifact_run.agent_run_id = response.agent_run_id
			  group by pack.title, variant.title, pack.updated_at
			  order by pack.updated_at desc
			  limit 1
			) artifact on true
			order by turn.turn_index
			""".trimIndent(),
			{ rs, _ ->
				ConversationTurn(
					userMessage = requireNotNull(rs.getString("user_message")),
					assistantMessage = rs.getString("assistant_message")?.trim()?.takeIf { it.isNotBlank() },
				)
			},
			maxCharacters,
			workspaceId,
			sessionId,
			maxCharacters,
			workspaceId,
			workspaceId,
		)
		return boundConversation(turns, maxCharacters)
	}

	private fun boundConversation(turns: List<ConversationTurn>, maxCharacters: Int): List<ChatConversationMessageRow> {
		val selected = ArrayDeque<List<ChatConversationMessageRow>>()
		var remaining = maxCharacters
		for (turn in turns.asReversed()) {
			val messages = buildList {
				add(ChatConversationMessageRow("user", turn.userMessage))
				turn.assistantMessage?.let { add(ChatConversationMessageRow("assistant", it)) }
			}
			val size = messages.sumOf { it.content.length }
			if (size <= remaining) {
				selected.addFirst(messages)
				remaining -= size
				continue
			}
			if (selected.isEmpty()) selected.addFirst(truncateTurn(turn, maxCharacters))
			break
		}
		return selected.flatten()
	}

	private fun truncateTurn(turn: ConversationTurn, maxCharacters: Int): List<ChatConversationMessageRow> {
		val assistant = turn.assistantMessage ?: return listOf(ChatConversationMessageRow("user", turn.userMessage.take(maxCharacters)))
		val reservedAssistantCharacters = minOf(assistant.length, maxCharacters / 2)
		val userLimit = minOf(turn.userMessage.length, maxCharacters - reservedAssistantCharacters)
		val assistantLimit = minOf(assistant.length, maxCharacters - userLimit)
		return buildList {
			turn.userMessage.take(userLimit).takeIf { it.isNotBlank() }?.let { add(ChatConversationMessageRow("user", it)) }
			assistant.take(assistantLimit).takeIf { it.isNotBlank() }?.let { add(ChatConversationMessageRow("assistant", it)) }
		}
	}
}

private data class ConversationTurn(val userMessage: String, val assistantMessage: String?)

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
	val responseText: String?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

data class ChatConversationMessageRow(
	val role: String,
	val content: String,
)
