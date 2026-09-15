package com.plot.api.chat

import com.plot.api.agent.AgentExecutionSnapshotPersistence
import com.plot.api.artifact.dto.ReplicateContentRequest
import com.plot.api.chat.dto.ChatAgentRunResponse
import com.plot.api.chat.dto.ChatResponseVersionDto
import com.plot.api.chat.dto.ContentBriefRequest
import com.plot.api.chat.dto.CreateChatAgentRunRequest
import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.content.ContentBrief
import com.plot.api.content.ContentSourceSnapshotService
import com.plot.api.content.ContentType
import com.plot.api.contentprofile.ContentProfileService
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import com.plot.api.agent.AgentRunDispatcher
import com.plot.api.agent.AgentRunIdempotencyConflictException
import com.plot.api.agent.AgentRunInputKind
import com.plot.api.agent.AgentRunInputRequest
import com.plot.api.agent.AgentRunQueryPersistence
import com.plot.api.agent.AgentRunRecord
import com.plot.api.agent.AgentRunStatus
import com.plot.api.agent.AgentToolAccessException
import com.plot.api.agent.ReadOnlyAgentTools
import com.plot.api.agent.AgentProperties
import com.plot.api.source.SourceManagedAccessGuard
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper

@Service
class ChatRunService(
	private val devContext: DevContext,
	private val sqlExecutor: JooqSqlExecutor,
	private val transactionExecutor: JooqTransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val agentRunQueryPersistence: AgentRunQueryPersistence,
	private val chatPersistence: ChatPersistence,
	private val snapshots: AgentExecutionSnapshotPersistence,
	private val tools: ReadOnlyAgentTools,
	private val properties: AgentProperties,
	private val objectMapper: ObjectMapper,
	private val sourceManagedAccessGuard: SourceManagedAccessGuard,
	private val agentRunDispatcher: AgentRunDispatcher,
	private val contentProfileService: ContentProfileService,
	private val contentSourceSnapshotService: ContentSourceSnapshotService,
	private val workspaceAccessService: WorkspaceAccessService,
	private val compatibilityWriter: ChatCompatibilityWriter,
	private val queries: ChatQueryService,
) {
	fun admit(request: CreateChatAgentRunRequest, idempotencyKey: String): ChatAgentRunResponse {
		sourceManagedAccessGuard.requireReadable()
		val run = admitInternal(
			principal = WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId),
			instruction = request.instruction,
			workSessionId = request.workSessionId,
			writingBlockIds = request.writingBlockIds,
			contentType = request.contentType,
			contentProfileRevisionId = request.contentProfileRevisionId,
			brief = request.brief,
			idempotencyKey = idempotencyKey,
			chatTitle = null,
		)
		return queries.toRunResponse(run)
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
		contentType = ContentType.CHANGELOG,
		contentProfileRevisionId = null,
		brief = null,
		idempotencyKey = idempotencyKey,
		chatTitle = chatTitle,
	)

	fun admitReplication(
		principal: WorkspacePrincipal,
		artifactId: UUID,
		request: ReplicateContentRequest,
		idempotencyKey: String,
	): ChatAgentRunResponse {
		sourceManagedAccessGuard.requireReadable()
		workspaceAccessService.requireWritable(principal.workspaceId)

		val workspaceId = principal.workspaceId
		val userId = principal.userId
		val key = idempotencyKey.trim()
		if (key.isBlank() || key.length > 200) {
			throw ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required")
		}

		val sourceRunInfo = sqlExecutor.query(
			"""
			select ar.id as agent_run_id, ar.work_session_id, ar.content_type, ar.content_profile_revision_id,
			       ar.instruction_snapshot, ar.content_brief_snapshot::text as content_brief_snapshot, cp.title
			from content_packs cp
			join generation_runs gr on gr.workspace_id = cp.workspace_id and gr.id = cp.generation_run_id
			join agent_runs ar on ar.workspace_id = gr.workspace_id and ar.id = gr.agent_run_id
			where cp.workspace_id = ? and cp.id = ?
			""".trimIndent(),
			{ rs, _ ->
				SourceArtifactRunInfo(
					agentRunId = requireNotNull(rs.getObject("agent_run_id", UUID::class.java)),
					workSessionId = rs.getObject("work_session_id", UUID::class.java),
					contentType = ContentType.parse(rs.getString("content_type")),
					contentProfileRevisionId = rs.getObject("content_profile_revision_id", UUID::class.java),
					instructionSnapshot = requireNotNull(rs.getString("instruction_snapshot")),
					contentBriefSnapshot = rs.getString("content_brief_snapshot"),
					title = rs.getString("title"),
				)
			},
			workspaceId,
			artifactId,
		).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Artifact not found")

		val snapshot = contentSourceSnapshotService.findOrCreateSnapshotForAgentRun(workspaceId, sourceRunInfo.agentRunId)

		// Re-validate that source is still active and connected
		if (snapshot.sourceScopeId != null) {
			val activeScopeCount = sqlExecutor.queryForObject(
				"""
				select count(*)
				from source_scopes scope
				join source_namespaces namespace
				  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
				 and namespace.provider = scope.provider
				join connection_namespace_bindings binding
				  on binding.workspace_id = namespace.workspace_id and binding.source_namespace_id = namespace.id
				 and binding.provider = namespace.provider
				join connections connection
				  on connection.workspace_id = binding.workspace_id and connection.id = binding.connection_id
				 and connection.provider = binding.provider
				where scope.workspace_id = ? and scope.id = ?
				  and scope.status = 'ACTIVE' and namespace.status = 'ACTIVE'
				  and binding.status = 'ACTIVE' and connection.status = 'ACTIVE'
				""".trimIndent(),
				Long::class.java,
				workspaceId,
				snapshot.sourceScopeId,
			) ?: 0L
			if (activeScopeCount == 0L) {
				throw ApiException(HttpStatus.CONFLICT, "SOURCE_NOT_READY", "The source repository or connection is no longer active")
			}
		}

		val frozenProfileRevisionId = request.contentProfileRevisionId?.let {
			contentProfileService.requireRevisionInWorkspace(workspaceId, it).id
		} ?: sourceRunInfo.contentProfileRevisionId ?: contentProfileService.currentRevisionId(workspaceId)

		val domainBrief = request.brief?.toDomain() ?: snapshot.briefSnapshotJson?.let {
			runCatching { objectMapper.readValue(it, ContentBrief::class.java) }.getOrNull()
		}
		val briefJson = domainBrief?.takeUnless { it.isBlank() }?.let(objectMapper::writeValueAsString)

		val normalizedInstruction = request.instruction?.trim()?.takeUnless { it.isEmpty() }
			?: defaultInstructionFor(request.contentType, sourceRunInfo.title)

		val fingerprint = buildString {
			append(snapshot.id).append('|')
			append(request.contentType.name).append('|')
			append(frozenProfileRevisionId ?: "none").append('|')
			append(domainBrief?.canonicalFingerprint().orEmpty()).append('|')
			append(normalizedInstruction)
		}.let { raw ->
			MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
				.joinToString("") { "%02x".format(it) }
		}

		val run = transactionExecutor.execute {
			sqlExecutor.queryForObject(
				"select pg_advisory_xact_lock(hashtextextended(?, 0))",
				{ _, _ -> Unit },
				"$workspaceId:$key",
			)
			findExisting(workspaceId, key)?.let { existing ->
				if (existing.requestFingerprint != fingerprint) throw AgentRunIdempotencyConflictException()
				return@execute existing
			}

			val chatId = resolveChat(null, workspaceId, userId, "Replicated ${request.contentType.name} from ${sourceRunInfo.title ?: sourceRunInfo.contentType.name}")
			val runId = uuidGenerator.next()
			val now = Instant.now()
			val inserted = sqlExecutor.update(
				"""
				insert into agent_runs (
				  id, workspace_id, routine_execution_id, routine_id, work_session_id, created_by_user_id,
				  origin, idempotency_key, request_fingerprint,
				  instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot, content_type,
				  content_profile_revision_id, content_brief_snapshot, source_snapshot_id,
				  status, max_attempts, created_at, updated_at
				) values (?, ?, null, null, ?, ?, 'CHAT', ?, ?, ?, 'chat-agent-v1', 'read-only-v1', ?::jsonb, ?,
				  ?, ?::jsonb, ?,
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
				objectMapper.writeValueAsString(properties.chatBudgetSnapshot()),
				request.contentType.name,
				frozenProfileRevisionId,
				briefJson,
				snapshot.id,
				properties.maxAttempts,
				Timestamp.from(now),
				Timestamp.from(now),
			)
			if (inserted == 0) {
				val raced = requireNotNull(findExisting(workspaceId, key))
				if (raced.requestFingerprint != fingerprint) throw AgentRunIdempotencyConflictException()
				return@execute raced
			}

				if (snapshot.sourceScopeId != null) {
					val sourceDisplayName = snapshot.inputs.firstOrNull { it.sourceScopeId == snapshot.sourceScopeId }?.sourceLabel
						?: requireActiveScopeDisplayName(workspaceId, snapshot.sourceScopeId)
					sqlExecutor.update(
						"""
						insert into agent_run_sources (
						  id, workspace_id, agent_run_id, source_scope_id, source_display_name, source_role, order_index,
						  captured_status, captured_status_changed_at, captured_at
						) values (?, ?, ?, ?, ?, 'CONTEXT', 0, 'ACTIVE', ?, ?)
					""".trimIndent(),
					uuidGenerator.next(),
						workspaceId,
						runId,
						snapshot.sourceScopeId,
						sourceDisplayName,
					Timestamp.from(now),
					Timestamp.from(now),
				)
			}

			// Generate brand-new agent_run_input rows with unique IDs [E16]
			snapshot.inputs.forEachIndexed { index, input ->
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

			val settingsJson = objectMapper.writeValueAsString(
				mapOf(
					"promptVersion" to "chat-agent-v1",
					"toolPolicyVersion" to "read-only-v1",
					"budgetSnapshot" to objectMapper.writeValueAsString(properties.chatBudgetSnapshot()),
					"contentType" to request.contentType.name,
					"contentProfileRevisionId" to frozenProfileRevisionId,
					"contentBriefSnapshot" to briefJson,
				),
			)
			compatibilityWriter.recordDirectChatRun(
				workspaceId = workspaceId,
				userId = userId,
				chatId = chatId,
				runId = runId,
				instruction = normalizedInstruction,
				fingerprint = fingerprint,
				settingsJson = settingsJson,
				sourceSnapshotId = snapshot.id,
				now = now,
			)

			val admittedRun = requireNotNull(agentRunQueryPersistence.findAgentRun(workspaceId, runId))
			scheduleAgentRunDispatchAfterCommit()
			admittedRun
		}

		return queries.toRunResponse(run)
	}

	private fun defaultInstructionFor(contentType: ContentType, title: String?): String = when (contentType) {
		ContentType.LAUNCH_ANNOUNCEMENT -> "Write a concise launch announcement highlighting who this helps and how to get started."
		ContentType.CHANGELOG -> "Generate release notes summarizing the key changes."
	}

	private fun admitInternal(
		principal: WorkspacePrincipal,
		instruction: String,
		workSessionId: UUID?,
		writingBlockIds: List<UUID>,
		contentType: ContentType,
		contentProfileRevisionId: UUID?,
		brief: ContentBriefRequest?,
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
		val frozenProfileRevisionId = contentProfileRevisionId?.let {
			contentProfileService.requireRevisionInWorkspace(workspaceId, it).id
		} ?: contentProfileService.currentRevisionId(workspaceId)
		val domainBrief = brief?.toDomain()
		val briefJson = domainBrief?.takeUnless { it.isBlank() }?.let(objectMapper::writeValueAsString)
		val fingerprint = fingerprint(
			CreateChatAgentRunRequest(
				instruction = normalizedInstruction,
				workSessionId = workSessionId,
				writingBlockIds = writingBlockIds,
				contentType = contentType,
				contentProfileRevisionId = frozenProfileRevisionId,
				brief = brief,
			),
			domainBrief,
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
				  instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot, content_type,
				  content_profile_revision_id, content_brief_snapshot,
				  status, max_attempts, created_at, updated_at
				) values (?, ?, null, null, ?, ?, 'CHAT', ?, ?, ?, 'chat-agent-v1', 'read-only-v1', ?::jsonb, ?,
				  ?, ?::jsonb,
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
				objectMapper.writeValueAsString(properties.chatBudgetSnapshot()),
				contentType.name,
				frozenProfileRevisionId,
				briefJson,
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
						  id, workspace_id, agent_run_id, source_scope_id, source_display_name, source_role, order_index,
						  captured_status, captured_status_changed_at, captured_at
						) values (?, ?, ?, ?, ?, 'CONTEXT', ?, 'ACTIVE', ?, ?)
					""".trimIndent(),
					uuidGenerator.next(),
						workspaceId,
						runId,
						source.id,
						source.displayName,
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

			val settingsJson = objectMapper.writeValueAsString(
				mapOf(
					"promptVersion" to "chat-agent-v1",
					"toolPolicyVersion" to "read-only-v1",
					"budgetSnapshot" to objectMapper.writeValueAsString(properties.chatBudgetSnapshot()),
					"contentType" to contentType.name,
					"contentProfileRevisionId" to frozenProfileRevisionId,
					"contentBriefSnapshot" to briefJson,
				),
			)
			val sourceSnapshot = contentSourceSnapshotService.findOrCreateSnapshotForAgentRun(workspaceId, runId)
			compatibilityWriter.recordDirectChatRun(
				workspaceId = workspaceId,
				userId = userId,
				chatId = chatId,
				runId = runId,
				instruction = normalizedInstruction,
				fingerprint = fingerprint,
				settingsJson = settingsJson,
				sourceSnapshotId = sourceSnapshot.id,
				now = now,
			)

			val run = requireNotNull(agentRunQueryPersistence.findAgentRun(workspaceId, runId))
			scheduleAgentRunDispatchAfterCommit()
			run
		}
	}

	private fun scheduleAgentRunDispatchAfterCommit() {
		if (!properties.autoDispatchEnabled) return
		if (
			TransactionSynchronizationManager.isSynchronizationActive() &&
				TransactionSynchronizationManager.isActualTransactionActive()
		) {
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() {
					agentRunDispatcher.dispatch()
				}
			})
		} else {
			agentRunDispatcher.dispatch()
		}
	}

	fun retry(targetVersionId: UUID, idempotencyKey: String): ChatResponseVersionDto {
		val workspaceId = devContext.devWorkspaceId
		val userId = devContext.devUserId
		val key = idempotencyKey.trim()
		if (key.isBlank() || key.length > 200) {
			throw ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required")
		}
		sourceManagedAccessGuard.requireReadable()
		workspaceAccessService.requireWritable(workspaceId)

		return transactionExecutor.execute {
			// Lock on idempotency key
			sqlExecutor.queryForObject(
				"select pg_advisory_xact_lock(hashtextextended(?, 0))",
				{ _, _ -> Unit },
				"$workspaceId:$key",
			)

			// 1. Find target version and turn first to identify context
			val targetVersion = chatPersistence.findResponseVersion(workspaceId, targetVersionId)
				?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Target response version not found")
			val turn = chatPersistence.findTurn(workspaceId, targetVersion.turnId)
				?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Turn not found")

			// 2. Check idempotency under lock
			findExisting(workspaceId, key)?.let { existing ->
				val existingVersion = chatPersistence.findResponseVersionByRunId(workspaceId, existing.id)
				if (existingVersion != null && existingVersion.turnId == turn.id) {
					return@execute queries.getVersion(existingVersion.id)
				}
				throw AgentRunIdempotencyConflictException()
			}

			// 3. Serialize at Chat session boundary (KTD5) using row lock on work_sessions
			sqlExecutor.query(
				"select id from work_sessions where workspace_id = ? and id = ? for update",
				{ rs, _ -> rs.getObject("id", UUID::class.java) },
				workspaceId,
				turn.workSessionId,
			).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Chat not found")

			// Lock the turn
			sqlExecutor.queryForObject(
				"select id from chat_turns where workspace_id = ? and id = ? for update",
				{ rs, _ -> rs.getObject("id", UUID::class.java) },
				workspaceId,
				turn.id,
			)

			// 4. Re-evaluate eligibility under lock
			val allTurns = chatPersistence.listTurns(workspaceId, turn.workSessionId)
			val maxTurnIndex = allTurns.maxOfOrNull { it.turnIndex } ?: 0
			if (turn.turnIndex != maxTurnIndex) {
				throw ApiException(HttpStatus.CONFLICT, "RETRY_INELIGIBLE", "Only the latest turn can be retried")
			}
			val versions = chatPersistence.listResponseVersionsForTurn(workspaceId, turn.id)
			val maxVersionIndex = versions.maxOfOrNull { it.versionIndex } ?: 0
			if (targetVersion.versionIndex != maxVersionIndex) {
				throw ApiException(HttpStatus.CONFLICT, "RETRY_INELIGIBLE", "Only the newest response version can be retried")
			}
			val targetRun = requireNotNull(agentRunQueryPersistence.findAgentRun(workspaceId, targetVersion.agentRunId))
			if (targetRun.status == AgentRunStatus.QUEUED || targetRun.status == AgentRunStatus.RUNNING) {
				throw ApiException(HttpStatus.CONFLICT, "RETRY_INELIGIBLE", "Current response is still in progress")
			}
			val targetEnvelope = snapshots.findEnvelopeForAgentRun(workspaceId, targetVersion.agentRunId)
				?: throw ApiException(HttpStatus.CONFLICT, "RETRY_INELIGIBLE", "Execution envelope is incomplete")
			if (!snapshots.hasCompleteFrozenEnvelope(workspaceId, targetRun.id, targetEnvelope)) {
				throw ApiException(HttpStatus.CONFLICT, "RETRY_INELIGIBLE", "Execution envelope is incomplete")
			}

			// 5. Admit new AgentRun
			val newRunId = uuidGenerator.next()
			val now = Instant.now()
			val newVersionIndex = maxVersionIndex + 1
			val newVersionId = uuidGenerator.next()
			val retryFingerprint = MessageDigest.getInstance("SHA-256")
				.digest("${targetRun.requestFingerprint}:retry:$newVersionIndex".toByteArray(Charsets.UTF_8))
				.joinToString("") { "%02x".format(it) }

			val inserted = sqlExecutor.update(
				"""
				insert into agent_runs (
				  id, workspace_id, routine_execution_id, routine_id, work_session_id, created_by_user_id,
				  origin, idempotency_key, request_fingerprint,
				  instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot, content_type,
				  content_profile_revision_id, content_brief_snapshot,
				  status, max_attempts, created_at, updated_at
				) values (?, ?, null, null, ?, ?, 'CHAT', ?, ?, ?, ?, ?, ?::jsonb, ?,
				  ?, ?::jsonb,
				  'QUEUED', ?, ?, ?)
				on conflict (workspace_id, idempotency_key) where origin = 'CHAT' do nothing
				""".trimIndent(),
				newRunId,
				workspaceId,
				turn.workSessionId,
				userId,
				key,
				retryFingerprint,
				targetRun.instructionSnapshot,
				targetRun.promptVersion,
				targetRun.toolPolicyVersion,
				targetRun.budgetSnapshotJson,
				targetRun.contentType.name,
				targetRun.contentProfileRevisionId,
				targetRun.contentBriefSnapshotJson,
				targetRun.maxAttempts,
				Timestamp.from(now),
				Timestamp.from(now),
			)
			if (inserted == 0) {
				val raced = requireNotNull(findExisting(workspaceId, key))
				val racedVersion = chatPersistence.findResponseVersionByRunId(workspaceId, raced.id)
				if (racedVersion != null && racedVersion.turnId == turn.id) {
					return@execute queries.getVersion(racedVersion.id)
				}
				throw AgentRunIdempotencyConflictException()
			}

			// 6. Deactivate previous version for this turn and insert new active version
			sqlExecutor.update(
				"update chat_response_versions set is_active = false, updated_at = ? where workspace_id = ? and turn_id = ? and is_active = true",
				Timestamp.from(now),
				workspaceId,
				turn.id,
			)
			sqlExecutor.update(
				"""
				insert into chat_response_versions (
				  id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id,
				  lineage_parent_version_id, is_active, created_at, updated_at
				) values (?, ?, ?, ?, ?, ?, ?, true, ?, ?)
				on conflict (workspace_id, agent_run_id) do nothing
				""".trimIndent(),
				newVersionId,
				workspaceId,
				turn.id,
				newVersionIndex,
				newRunId,
				userId,
				targetVersion.id,
				Timestamp.from(now),
				Timestamp.from(now),
			)

			// 7. Insert new chat_execution_envelopes row
			val newEnvelopeId = uuidGenerator.next()
			snapshots.insertEnvelope(
				newEnvelopeId, workspaceId, newRunId, targetEnvelope.fingerprintVersion,
				retryFingerprint, targetEnvelope.generationSettingsJson, targetEnvelope.sourceSnapshotId, now,
			)

			// 8. Clone sources from targetRun
			val targetSources = agentRunQueryPersistence.listAgentRunSources(workspaceId, targetRun.id)
			for (source in targetSources) {
				sqlExecutor.update(
					"""
						insert into agent_run_sources (
						  id, workspace_id, agent_run_id, source_scope_id, source_display_name, source_role, order_index,
						  captured_status, captured_status_changed_at, captured_at
						) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""".trimIndent(),
					uuidGenerator.next(),
						workspaceId,
						newRunId,
						source.sourceScopeId,
						source.displayName,
					source.role.name,
					source.orderIndex,
					source.capturedStatus,
					Timestamp.from(source.capturedStatusChangedAt),
					Timestamp.from(now),
				)
			}

			// 9. Clone inputs from targetRun
			val targetInputs = agentRunQueryPersistence.listAgentRunInputs(workspaceId, targetRun.id)
			for (input in targetInputs) {
				sqlExecutor.update(
					"""
					insert into agent_run_inputs (
					  id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
					  source_provider, source_kind, source_label, input_kind, order_index,
					  activity_sequence, snapshot_title, snapshot_body, snapshot_excerpt, original_url,
					  source_created_at, source_updated_at, content_hash, captured_at
					) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""".trimIndent(),
					uuidGenerator.next(),
					workspaceId,
					newRunId,
					input.routineId,
					input.sourceScopeId,
					input.writingBlockId,
					input.sourceProvider,
					input.sourceKind,
					input.sourceLabel,
					input.inputKind.name,
					input.orderIndex,
					input.activitySequence,
					input.snapshotTitle,
					input.snapshotBody,
					input.snapshotExcerpt,
					input.originalUrl,
					input.sourceCreatedAt?.let { Timestamp.from(it) },
					input.sourceUpdatedAt?.let { Timestamp.from(it) },
					input.contentHash,
					Timestamp.from(now),
				)
			}

			// 10. Clone tool transcript entries (if any)
			snapshots.copyTranscript(workspaceId, targetEnvelope.id, newEnvelopeId, now)

			// 11. Dispatch
			scheduleAgentRunDispatchAfterCommit()

			// 12. Return the new version DTO
			queries.getVersion(newVersionId)
		}
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
		val hasActiveRun = sqlExecutor.queryForObject(
			"select exists(select 1 from agent_runs where workspace_id = ? and work_session_id = ? and status in ('QUEUED', 'RUNNING'))",
			Boolean::class.java,
			workspaceId,
			workSessionId,
		) ?: false
		if (hasActiveRun) {
			throw ApiException(HttpStatus.CONFLICT, "CHAT_RUN_IN_PROGRESS", "Wait for the current response to complete before sending another message")
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
		select scope.id, scope.display_name,
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
			{ rs, _ -> FrozenSource(requireNotNull(rs.getObject("id", UUID::class.java)), requireNotNull(rs.getString("display_name")), requireNotNull(rs.getTimestamp("lifecycle_version_at")).toInstant()) },
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
		chatPersistence.findChatAgentRunByIdempotencyKey(workspaceId, key, forUpdate = true)

	private fun fingerprint(request: CreateChatAgentRunRequest, brief: ContentBrief?): String {
		val canonical = buildString {
			append(request.workSessionId ?: "new").append('|')
			append(request.contentType.name).append('|')
			append(request.contentProfileRevisionId ?: "none").append('|')
			append(brief?.canonicalFingerprint().orEmpty()).append('|')
			append(request.instruction).append('|')
			request.writingBlockIds.forEach { append(it).append(',') }
		}
		return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	private fun requireActiveScopeDisplayName(workspaceId: UUID, sourceScopeId: UUID): String = requireNotNull(sqlExecutor.queryForObject(
		"select display_name from source_scopes where workspace_id = ? and id = ? and status = 'ACTIVE'",
		String::class.java,
		workspaceId,
		sourceScopeId,
	))
	private data class FrozenSource(val id: UUID, val displayName: String, val lifecycleVersionAt: Instant)
	private data class ChatSessionRow(val routineExecutionId: UUID?)
	private data class SourceArtifactRunInfo(
		val agentRunId: UUID,
		val workSessionId: UUID?,
		val contentType: ContentType,
		val contentProfileRevisionId: UUID?,
		val instructionSnapshot: String,
		val contentBriefSnapshot: String?,
		val title: String?,
	)
}
