package com.plot.api.routine

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.dev.DevContext
import com.plot.api.routine.dto.ChatAgentRunResponse
import com.plot.api.routine.dto.ChatAgentArtifactSummaryResponse
import com.plot.api.routine.dto.CreateChatAgentRunRequest
import com.plot.api.routine.dto.toChatResponse
import com.plot.api.source.SourceManagedAccessGuard
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import com.plot.api.persistence.SqlRow
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class ChatAgentAdmissionService(
	private val devContext: DevContext,
	private val sqlExecutor: JooqSqlExecutor,
	private val transactionExecutor: JooqTransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val agentRunQueryPersistence: AgentRunQueryPersistence,
	private val tools: ReadOnlyAgentTools,
	private val properties: RoutineAgentProperties,
	private val objectMapper: ObjectMapper,
	private val sourceManagedAccessGuard: SourceManagedAccessGuard,
) {
	fun admit(request: CreateChatAgentRunRequest, idempotencyKey: String): ChatAgentRunResponse {
		sourceManagedAccessGuard.requireReadable()
		val run = admitInternal(
			principal = WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId),
			instruction = request.instruction,
			workSessionId = request.workSessionId,
			writingBlockIds = request.writingBlockIds,
			idempotencyKey = idempotencyKey,
			chatTitle = null,
		)
		return run.toChatResponseFor(agentRunQueryPersistence)
	}

	fun admitAutomated(
		principal: WorkspacePrincipal,
		instruction: String,
		writingBlockIds: List<UUID>,
		idempotencyKey: String,
		chatTitle: String,
	): AgentRunRecord = admitInternal(
		principal = principal,
		instruction = instruction,
		workSessionId = null,
		writingBlockIds = writingBlockIds,
		idempotencyKey = idempotencyKey,
		chatTitle = chatTitle,
	)

	private fun admitInternal(
		principal: WorkspacePrincipal,
		instruction: String,
		workSessionId: UUID?,
		writingBlockIds: List<UUID>,
		idempotencyKey: String,
		chatTitle: String?,
	): AgentRunRecord {
		val workspaceId = principal.workspaceId
		val userId = principal.userId
		val key = idempotencyKey.trim()
		if (key.isBlank() || key.length > 200) {
			throw ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required")
		}
		val normalizedInstruction = instruction.trim()
		if (writingBlockIds.distinct().size != writingBlockIds.size) {
			throw ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_SOURCE_ITEMS", "Writing Block IDs must be unique")
		}
		val fingerprint = fingerprint(
			CreateChatAgentRunRequest(
				instruction = normalizedInstruction,
				workSessionId = workSessionId,
				writingBlockIds = writingBlockIds,
			),
		)
		return transactionExecutor.execute {
			// A transaction-scoped advisory lock prevents two identical requests from
		// creating duplicate Chats before the partial idempotency index is reached.
			sqlExecutor.queryForObject(
				"select pg_advisory_xact_lock(hashtextextended(?, 0))",
				{ _, _ -> Unit },
				"$workspaceId:$key",
			)
			findExisting(workspaceId, key)?.let { existing ->
				if (existing.requestFingerprint != fingerprint) throw AgentRunIdempotencyConflictException()
				return@execute existing
			}

			val sources = lockActiveSources(workspaceId)
			if (sources.isEmpty()) {
				throw ApiException(
					HttpStatus.CONFLICT,
					"SOURCE_NOT_READY",
					"Connect an active source before starting a Chat",
				)
			}
			val chatId = resolveChat(workSessionId, workspaceId, userId, chatTitle ?: normalizedInstruction)
			val runId = uuidGenerator.next()
			val now = Instant.now()
			val inserted = sqlExecutor.update(
				"""
				insert into agent_runs (
				  id, workspace_id, routine_execution_id, routine_id, work_session_id, created_by_user_id,
				  origin, idempotency_key, request_fingerprint,
				  instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot,
				  status, max_attempts, created_at, updated_at
				) values (?, ?, null, null, ?, ?, 'CHAT', ?, ?, ?, 'chat-agent-v1', 'read-only-v1', ?::jsonb,
				  'QUEUED', ?, ?, ?)
				on conflict (workspace_id, idempotency_key) where origin = 'CHAT' do nothing
				""".trimIndent(),
				runId,
				workspaceId,
				chatId,
				userId,
				key,
				fingerprint,
				normalizedInstruction,
				budgetSnapshot(),
				properties.maxAttempts,
				Timestamp.from(now),
				Timestamp.from(now),
			)
			if (inserted == 0) {
				val raced = requireNotNull(findExisting(workspaceId, key))
				if (raced.requestFingerprint != fingerprint) throw AgentRunIdempotencyConflictException()
				return@execute raced
			}

			sources.forEachIndexed { index, source ->
				sqlExecutor.update(
					"""
					insert into agent_run_sources (
					  id, workspace_id, agent_run_id, source_scope_id, source_role, order_index,
					  captured_status, captured_status_changed_at, captured_at
					) values (?, ?, ?, ?, 'CONTEXT', ?, 'ACTIVE', ?, ?)
					""".trimIndent(),
					uuidGenerator.next(),
					workspaceId,
					runId,
					source.id,
					index,
					Timestamp.from(source.lifecycleVersionAt),
					Timestamp.from(now),
				)
			}

			writingBlockIds.forEachIndexed { index, blockId ->
				val sourceScopeId = findReadableBlockSource(workspaceId, blockId, sources.map { it.id })
					?: throw ApiException(HttpStatus.BAD_REQUEST, "SOURCE_ITEM_NOT_READY", "A selected source item is unavailable")
				val input = try {
					tools.readWritingBlock(workspaceId, runId, sourceScopeId, blockId).adoptedInput
				} catch (failure: AgentToolAccessException) {
					throw ApiException(HttpStatus.BAD_REQUEST, failure.safeCode, "A selected source item is unavailable")
				}
				val seed = requireNotNull(input).copy(
					inputKind = AgentRunInputKind.SEED,
					routineId = null,
					activitySequence = null,
					orderIndex = index,
				)
				insertSeed(workspaceId, runId, seed, now)
			}

			val run = requireNotNull(agentRunQueryPersistence.findAgentRun(workspaceId, runId))
			run
		}
	}

	fun get(id: UUID): ChatAgentRunResponse {
		val run = agentRunQueryPersistence.findAgentRun(devContext.devWorkspaceId, id)
			?.takeIf { it.origin == AgentRunOrigin.CHAT }
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Agent run not found")
		return run.toChatResponseFor(agentRunQueryPersistence)
	}

	fun listForSession(sessionId: UUID): List<ChatAgentRunResponse> {
		if (!agentRunQueryPersistence.sessionExists(devContext.devWorkspaceId, sessionId)) {
			throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Chat not found")
		}
		return agentRunQueryPersistence.listSessionAgentRuns(devContext.devWorkspaceId, sessionId)
			.map { it.toChatResponseFor(agentRunQueryPersistence) }
	}

	private fun AgentRunRecord.toChatResponseFor(persistence: AgentRunQueryPersistence): ChatAgentRunResponse {
		return toChatResponse(artifact = persistence.findArtifactForAgentRun(workspaceId, id)?.let {
			ChatAgentArtifactSummaryResponse(it.id, it.status, it.title, it.updatedAt)
		})
	}

	private fun resolveChat(workSessionId: UUID?, workspaceId: UUID, userId: UUID, title: String): UUID {
		if (workSessionId == null) {
			val id = uuidGenerator.next()
			val now = Instant.now()
			sqlExecutor.update(
				"""
				insert into work_sessions (
				  id, workspace_id, title, status, created_by_user_id, latest_generation_run_id,
				  last_activity_at, created_at, updated_at
				) values (?, ?, ?, 'OPEN', ?, null, ?, ?, ?)
				""".trimIndent(),
				id,
				workspaceId,
				title,
				userId,
				Timestamp.from(now),
				Timestamp.from(now),
				Timestamp.from(now),
			)
			return id
		}

		val session = sqlExecutor.query(
			"select routine_execution_id from work_sessions where workspace_id = ? and id = ? for update",
			{ rs, _ -> ChatSessionRow(rs.getObject("routine_execution_id", UUID::class.java)) },
			workspaceId,
			workSessionId,
		).singleOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Chat not found")
		if (session.routineExecutionId != null) {
			throw ApiException(HttpStatus.CONFLICT, "CHAT_NOT_INTERACTIVE", "Routine Chats cannot receive interactive requests")
		}
		sqlExecutor.update(
			"update work_sessions set last_activity_at = ?, updated_at = ? where workspace_id = ? and id = ?",
			Timestamp.from(Instant.now()),
			Timestamp.from(Instant.now()),
			workspaceId,
			workSessionId,
		)
		return workSessionId
	}

	private fun lockActiveSources(workspaceId: UUID): List<FrozenSource> = sqlExecutor.query(
		"""
		select scope.id,
		       greatest(scope.status_changed_at, namespace.updated_at, binding.updated_at, connection.updated_at)
		         as lifecycle_version_at
		from source_scopes scope
		join source_namespaces namespace
		  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
		 and namespace.provider = scope.provider and namespace.status = 'ACTIVE'
		join connection_namespace_bindings binding
		  on binding.workspace_id = namespace.workspace_id and binding.source_namespace_id = namespace.id
		 and binding.provider = namespace.provider and binding.status = 'ACTIVE'
		join connections connection
		  on connection.workspace_id = binding.workspace_id and connection.id = binding.connection_id
		 and connection.provider = binding.provider and connection.status = 'ACTIVE'
		where scope.workspace_id = ? and scope.provider = 'GITHUB' and scope.status = 'ACTIVE'
		order by scope.id
		for update of scope, namespace, binding, connection
		""".trimIndent(),
		{ rs, _ -> FrozenSource(requireNotNull(rs.getObject("id", UUID::class.java)), requireNotNull(rs.getTimestamp("lifecycle_version_at")).toInstant()) },
		workspaceId,
	)

	private fun findReadableBlockSource(workspaceId: UUID, blockId: UUID, sourceScopeIds: List<UUID>): UUID? {
		if (sourceScopeIds.isEmpty()) return null
		val placeholders = sourceScopeIds.joinToString(",") { "?" }
		return sqlExecutor.query(
			"""
			select membership.source_scope_id
			from writing_block_scopes membership
			join writing_blocks block
			  on block.workspace_id = membership.workspace_id and block.id = membership.writing_block_id
			where membership.workspace_id = ? and membership.writing_block_id = ?
			  and membership.source_scope_id in ($placeholders)
			  and membership.status = 'ACTIVE' and block.status = 'ACTIVE'
			order by membership.source_scope_id
			""".trimIndent(),
			{ rs, _ -> requireNotNull(rs.getObject("source_scope_id", UUID::class.java)) },
			workspaceId,
			blockId,
			*sourceScopeIds.toTypedArray(),
		).firstOrNull()
	}

	private fun insertSeed(workspaceId: UUID, agentRunId: UUID, input: AgentRunInputRequest, now: Instant) {
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
			agentRunId,
			input.sourceScopeId,
			input.writingBlockId,
			input.sourceProvider,
			input.sourceKind,
			input.sourceLabel,
			input.orderIndex,
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

	private fun findExisting(workspaceId: UUID, key: String): AgentRunRecord? =
		agentRunQueryPersistence.findChatAgentRunByIdempotencyKey(workspaceId, key, forUpdate = true)

	private fun budgetSnapshot(): String = objectMapper.writeValueAsString(
		mapOf(
			"maxModelCalls" to properties.maxModelCalls,
			"maxToolCalls" to properties.maxToolCalls,
			"maxRunDurationMillis" to properties.maxRunDuration.toMillis(),
			"maxInputCharacters" to properties.maxInputCharacters,
			"maxEvidenceCharacters" to properties.maxEvidenceCharacters,
			"truncatedSeed" to false,
		),
	)

	private fun fingerprint(request: CreateChatAgentRunRequest): String {
		val canonical = buildString {
			append(request.workSessionId ?: "new").append('|')
			append(request.instruction).append('|')
			request.writingBlockIds.forEach { append(it).append(',') }
		}
		return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	private data class FrozenSource(val id: UUID, val lifecycleVersionAt: Instant)
	private data class ChatSessionRow(val routineExecutionId: UUID?)
}
