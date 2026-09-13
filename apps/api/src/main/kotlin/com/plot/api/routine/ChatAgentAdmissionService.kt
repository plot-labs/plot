package com.plot.api.routine

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.artifact.dto.ReplicateContentRequest
import com.plot.api.content.ContentBrief
import com.plot.api.content.ContentSourceSnapshotService
import com.plot.api.content.ContentType
import com.plot.api.contentprofile.ContentProfileService
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.routine.dto.ChatAgentRunResponse
import com.plot.api.routine.dto.ChatAgentArtifactSummaryResponse
import com.plot.api.routine.dto.ChatResponseVersionDto
import com.plot.api.routine.dto.ChatTurnDto
import com.plot.api.routine.dto.ContentBriefRequest
import com.plot.api.routine.dto.CreateChatAgentRunRequest
import com.plot.api.routine.dto.RetryEligibilityDto
import com.plot.api.routine.dto.toChatResponse
import com.plot.api.source.SourceManagedAccessGuard
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
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
	private val agentRunDispatcher: AgentRunDispatcher,
	private val contentProfileService: ContentProfileService,
	private val contentSourceSnapshotService: ContentSourceSnapshotService,
	private val workspaceAccessService: WorkspaceAccessService,
	private val compatibilityWriter: ChatCompatibilityWriter? = null,
) {
	private fun writer(): ChatCompatibilityWriter = compatibilityWriter ?: ChatCompatibilityWriter(sqlExecutor, uuidGenerator)
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
				budgetSnapshot(),
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
				sqlExecutor.update(
					"""
					insert into agent_run_sources (
					  id, workspace_id, agent_run_id, source_scope_id, source_role, order_index,
					  captured_status, captured_status_changed_at, captured_at
					) values (?, ?, ?, ?, 'CONTEXT', 0, 'ACTIVE', ?, ?)
					""".trimIndent(),
					uuidGenerator.next(),
					workspaceId,
					runId,
					snapshot.sourceScopeId,
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
					"budgetSnapshot" to budgetSnapshot(),
					"contentType" to request.contentType.name,
					"contentProfileRevisionId" to frozenProfileRevisionId,
					"contentBriefSnapshot" to briefJson,
				),
			)
			writer().recordDirectChatRun(
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

		return run.toChatResponseFor(agentRunQueryPersistence)
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
				budgetSnapshot(),
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

			val settingsJson = objectMapper.writeValueAsString(
				mapOf(
					"promptVersion" to "chat-agent-v1",
					"toolPolicyVersion" to "read-only-v1",
					"budgetSnapshot" to budgetSnapshot(),
					"contentType" to contentType.name,
					"contentProfileRevisionId" to frozenProfileRevisionId,
					"contentBriefSnapshot" to briefJson,
				),
			)
			writer().recordDirectChatRun(
				workspaceId = workspaceId,
				userId = userId,
				chatId = chatId,
				runId = runId,
				instruction = normalizedInstruction,
				fingerprint = fingerprint,
				settingsJson = settingsJson,
				sourceSnapshotId = null,
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

	fun listTurnsForSession(sessionId: UUID, selectedVersionId: UUID? = null): List<ChatTurnDto> {
		val workspaceId = devContext.devWorkspaceId
		if (!agentRunQueryPersistence.sessionExists(workspaceId, sessionId)) {
			throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Chat not found")
		}
		workspaceAccessService.requireActiveWorkspace(workspaceId)
		ensureTurnsForSession(workspaceId, sessionId)

		val turns = agentRunQueryPersistence.listTurns(workspaceId, sessionId)
		if (turns.isEmpty()) return emptyList()

		val maxTurnIndex = turns.maxOf { it.turnIndex }
		val result = mutableListOf<ChatTurnDto>()

		for (turn in turns) {
			val versions = agentRunQueryPersistence.listResponseVersionsForTurn(workspaceId, turn.id)
			val maxVersionIndex = versions.maxOfOrNull { it.versionIndex } ?: 0
			val isLatestTurn = turn.turnIndex == maxTurnIndex

			val versionDtos = versions.map { v ->
				val run = requireNotNull(agentRunQueryPersistence.findAgentRun(workspaceId, v.agentRunId))
				val artifact = agentRunQueryPersistence.findArtifactForAgentRun(workspaceId, v.agentRunId)?.let {
					ChatAgentArtifactSummaryResponse(it.id, it.status, it.title, run.contentType, it.updatedAt)
				}
				val isNewestVersion = v.versionIndex == maxVersionIndex
				val envelope = agentRunQueryPersistence.findEnvelopeForAgentRun(workspaceId, v.agentRunId)
				val eligibility = computeEligibility(
					workspaceId = workspaceId,
					isLatestTurn = isLatestTurn,
					isNewestVersion = isNewestVersion,
					runStatus = run.status,
					envelope = envelope,
				)

				ChatResponseVersionDto(
					id = v.id,
					turnId = turn.id,
					versionIndex = v.versionIndex,
					agentRunId = v.agentRunId,
					status = run.status,
					failureCode = run.failureCode,
					instruction = run.instructionSnapshot,
					artifactId = artifact?.id,
					artifact = artifact,
					retryEligibility = eligibility,
					createdAt = v.createdAt,
					updatedAt = v.updatedAt,
				)
			}

			val chosenVersionId = if (selectedVersionId != null && versionDtos.any { it.id == selectedVersionId }) {
				selectedVersionId
			} else {
				versionDtos.lastOrNull()?.id ?: turn.id
			}

			result.add(
				ChatTurnDto(
					id = turn.id,
					workSessionId = sessionId,
					turnIndex = turn.turnIndex,
					userMessage = turn.userMessage,
					versions = versionDtos,
					selectedVersionId = chosenVersionId,
					createdAt = turn.createdAt,
					updatedAt = turn.updatedAt,
				)
			)
		}

		return result
	}

	fun getResponseVersion(versionId: UUID): ChatResponseVersionDto {
		val workspaceId = devContext.devWorkspaceId
		val v = agentRunQueryPersistence.findResponseVersion(workspaceId, versionId)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Response version not found")
		val turn = requireNotNull(agentRunQueryPersistence.findTurn(workspaceId, v.turnId))
		val turns = agentRunQueryPersistence.listTurns(workspaceId, turn.workSessionId)
		val maxTurnIndex = turns.maxOfOrNull { it.turnIndex } ?: 0
		val versions = agentRunQueryPersistence.listResponseVersionsForTurn(workspaceId, turn.id)
		val maxVersionIndex = versions.maxOfOrNull { it.versionIndex } ?: 0
		val run = requireNotNull(agentRunQueryPersistence.findAgentRun(workspaceId, v.agentRunId))
		val artifact = agentRunQueryPersistence.findArtifactForAgentRun(workspaceId, v.agentRunId)?.let {
			ChatAgentArtifactSummaryResponse(it.id, it.status, it.title, run.contentType, it.updatedAt)
		}
		val envelope = agentRunQueryPersistence.findEnvelopeForAgentRun(workspaceId, v.agentRunId)
		val eligibility = computeEligibility(
			workspaceId = workspaceId,
			isLatestTurn = turn.turnIndex == maxTurnIndex,
			isNewestVersion = v.versionIndex == maxVersionIndex,
			runStatus = run.status,
			envelope = envelope,
		)
		return ChatResponseVersionDto(
			id = v.id,
			turnId = turn.id,
			versionIndex = v.versionIndex,
			agentRunId = v.agentRunId,
			status = run.status,
			failureCode = run.failureCode,
			instruction = run.instructionSnapshot,
			artifactId = artifact?.id,
			artifact = artifact,
			retryEligibility = eligibility,
			createdAt = v.createdAt,
			updatedAt = v.updatedAt,
		)
	}

	fun computeEligibility(
		workspaceId: UUID,
		isLatestTurn: Boolean,
		isNewestVersion: Boolean,
		runStatus: AgentRunStatus,
		envelope: ChatExecutionEnvelopeRow?,
	): RetryEligibilityDto {
		if (!isLatestTurn) {
			return RetryEligibilityDto(eligible = false, reason = "NOT_LATEST_TURN")
		}
		if (!isNewestVersion) {
			return RetryEligibilityDto(eligible = false, reason = "NOT_LATEST_VERSION")
		}
		if (runStatus == AgentRunStatus.QUEUED || runStatus == AgentRunStatus.RUNNING) {
			return RetryEligibilityDto(eligible = false, reason = "RUN_NOT_TERMINAL")
		}
		if (envelope == null || envelope.envelopeFingerprint.isBlank() || envelope.generationSettingsJson.isBlank()) {
			return RetryEligibilityDto(eligible = false, reason = "INCOMPLETE_ENVELOPE")
		}
		try {
			workspaceAccessService.requireWritable(workspaceId)
		} catch (_: Exception) {
			return RetryEligibilityDto(eligible = false, reason = "UNAUTHORIZED")
		}
		return RetryEligibilityDto(eligible = true, reason = null)
	}

	private data class OrphanRun(
		val id: UUID,
		val instruction: String,
		val userId: UUID,
		val createdAt: Instant,
		val fingerprint: String,
	)

	private fun ensureTurnsForSession(workspaceId: UUID, sessionId: UUID) {
		val orphanRuns = sqlExecutor.query(
			"""
			select a.id, a.instruction_snapshot, a.created_by_user_id, a.created_at, a.request_fingerprint
			from agent_runs a
			left join chat_response_versions v on v.workspace_id = a.workspace_id and v.agent_run_id = a.id
			where a.workspace_id = ? and a.work_session_id = ? and v.id is null
			order by a.created_at, a.id
			""".trimIndent(),
			{ rs, _ ->
				OrphanRun(
					id = requireNotNull(rs.getObject("id", UUID::class.java)),
					instruction = rs.getString("instruction_snapshot") ?: "",
					userId = rs.getObject("created_by_user_id", UUID::class.java) ?: devContext.devUserId,
					createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
					fingerprint = rs.getString("request_fingerprint") ?: "legacy-fingerprint",
				)
			},
			workspaceId,
			sessionId,
		)
		for (orphan in orphanRuns) {
			writer().recordDirectChatRun(
				workspaceId = workspaceId,
				userId = orphan.userId,
				chatId = sessionId,
				runId = orphan.id,
				instruction = orphan.instruction,
				fingerprint = orphan.fingerprint,
				settingsJson = "{}",
				sourceSnapshotId = null,
				now = orphan.createdAt,
			)
		}
	}

	private fun AgentRunRecord.toChatResponseFor(persistence: AgentRunQueryPersistence): ChatAgentRunResponse {
		val brief = contentBriefSnapshotJson?.let { objectMapper.readValue(it, ContentBrief::class.java) }
		return toChatResponse(
			artifact = persistence.findArtifactForAgentRun(workspaceId, id)?.let {
				ChatAgentArtifactSummaryResponse(it.id, it.status, it.title, contentType, it.updatedAt)
			},
			brief = brief,
		)
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

	private data class FrozenSource(val id: UUID, val lifecycleVersionAt: Instant)
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
