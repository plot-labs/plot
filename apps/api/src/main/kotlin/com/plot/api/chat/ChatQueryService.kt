package com.plot.api.chat

import com.plot.api.chat.dto.ChatAgentArtifactSummaryResponse
import com.plot.api.chat.dto.ChatAgentRunResponse
import com.plot.api.chat.dto.ChatResponseCitationDto
import com.plot.api.chat.dto.ChatResponseSourceDto
import com.plot.api.chat.dto.ChatResponseVersionDto
import com.plot.api.chat.dto.ChatTurnDto
import com.plot.api.chat.dto.RetryEligibilityDto
import com.plot.api.chat.dto.toChatResponse
import com.plot.api.common.ApiException
import com.plot.api.content.ContentBrief
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.agent.AgentRunOrigin
import com.plot.api.agent.AgentRunQueryPersistence
import com.plot.api.agent.AgentRunRecord
import com.plot.api.agent.AgentRunStatus
import com.plot.api.agent.ChatExecutionEnvelopeRow
import com.plot.api.agent.ChatResponseVersionRow
import com.plot.api.agent.ChatTurnRow
import com.plot.api.agent.AgentProperties
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class ChatQueryService(
	private val devContext: DevContext,
	private val sqlExecutor: JooqSqlExecutor,
	private val agentRunQueryPersistence: AgentRunQueryPersistence,
	private val workspaceAccessService: WorkspaceAccessService,
	private val objectMapper: ObjectMapper,
	private val properties: AgentProperties,
	private val compatibilityWriter: ChatCompatibilityWriter,
) {
	fun getRun(id: UUID): ChatAgentRunResponse {
		val run = agentRunQueryPersistence.findAgentRun(devContext.devWorkspaceId, id)
			?.takeIf { it.origin == AgentRunOrigin.CHAT }
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Agent run not found")
		return toRunResponse(run)
	}

	fun listForSession(sessionId: UUID): List<ChatAgentRunResponse> {
		if (!agentRunQueryPersistence.sessionExists(devContext.devWorkspaceId, sessionId)) {
			throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Chat not found")
		}
		return agentRunQueryPersistence.listSessionAgentRuns(devContext.devWorkspaceId, sessionId)
			.map { toRunResponse(it) }
	}

	fun listTurnsForSession(sessionId: UUID, selectedVersionId: UUID? = null): List<ChatTurnDto> {
		val workspaceId = devContext.devWorkspaceId
		if (!agentRunQueryPersistence.sessionExists(workspaceId, sessionId)) {
			throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Chat not found")
		}
		workspaceAccessService.requireActiveWorkspace(workspaceId)
		projectLegacyRunsIntoTurns(workspaceId, sessionId)

		val turns = agentRunQueryPersistence.listTurns(workspaceId, sessionId)
		if (turns.isEmpty()) return emptyList()

		val maxTurnIndex = turns.maxOf { it.turnIndex }
		val result = mutableListOf<ChatTurnDto>()

		for (turn in turns) {
			val versions = agentRunQueryPersistence.listResponseVersionsForTurn(workspaceId, turn.id)
			val maxVersionIndex = versions.maxOfOrNull { it.versionIndex } ?: 0
			val isLatestTurn = turn.turnIndex == maxTurnIndex

			val versionDtos = versions.map { version ->
				toVersionResponse(workspaceId, turn, version, isLatestTurn, version.versionIndex == maxVersionIndex)
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

	fun getVersion(versionId: UUID): ChatResponseVersionDto {
		val workspaceId = devContext.devWorkspaceId
		val v = agentRunQueryPersistence.findResponseVersion(workspaceId, versionId)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Response version not found")
		val turn = requireNotNull(agentRunQueryPersistence.findTurn(workspaceId, v.turnId))
		val turns = agentRunQueryPersistence.listTurns(workspaceId, turn.workSessionId)
		val maxTurnIndex = turns.maxOfOrNull { it.turnIndex } ?: 0
		val versions = agentRunQueryPersistence.listResponseVersionsForTurn(workspaceId, turn.id)
		val maxVersionIndex = versions.maxOfOrNull { it.versionIndex } ?: 0
		return toVersionResponse(workspaceId, turn, v, turn.turnIndex == maxTurnIndex, v.versionIndex == maxVersionIndex)
	}

	private fun toVersionResponse(
		workspaceId: UUID,
		turn: ChatTurnRow,
		v: ChatResponseVersionRow,
		isLatestTurn: Boolean,
		isNewestVersion: Boolean,
	): ChatResponseVersionDto {
		val run = requireNotNull(agentRunQueryPersistence.findAgentRun(workspaceId, v.agentRunId))
		val artifact = agentRunQueryPersistence.findArtifactForAgentRun(workspaceId, v.agentRunId)?.let {
			ChatAgentArtifactSummaryResponse(it.id, it.status, it.title, run.contentType, it.updatedAt)
		}
		val envelope = agentRunQueryPersistence.findEnvelopeForAgentRun(workspaceId, v.agentRunId)
		val eligibility = computeEligibility(
			workspaceId = workspaceId,
			isLatestTurn = isLatestTurn,
			isNewestVersion = isNewestVersion,
			runStatus = run.status,
			envelope = envelope,
			agentRunId = v.agentRunId,
		)
		val sources = agentRunQueryPersistence.listAgentRunSources(workspaceId, v.agentRunId).map { s ->
			ChatResponseSourceDto(
				id = s.sourceScopeId,
					displayName = s.displayName ?: s.role.name,
				role = s.role.name,
			)
		}
		val citations = agentRunQueryPersistence.listAgentRunInputs(workspaceId, v.agentRunId).map { i ->
			ChatResponseCitationDto(
				id = i.writingBlockId,
				title = i.snapshotTitle,
				excerpt = i.snapshotExcerpt.orEmpty(),
				url = i.originalUrl,
			)
		}
		return ChatResponseVersionDto(
			id = v.id,
			turnId = turn.id,
			versionIndex = v.versionIndex,
			agentRunId = v.agentRunId,
			lineageParentVersionId = v.lineageParentVersionId,
			status = run.status,
			failureCode = run.failureCode,
			instruction = run.instructionSnapshot,
			artifactId = artifact?.id,
			artifact = artifact,
			retryEligibility = eligibility,
			sources = sources,
			citations = citations,
			createdAt = v.createdAt,
			updatedAt = v.updatedAt,
		)
	}

	private fun computeEligibility(
		workspaceId: UUID,
		isLatestTurn: Boolean,
		isNewestVersion: Boolean,
		runStatus: AgentRunStatus,
		envelope: ChatExecutionEnvelopeRow?,
		agentRunId: UUID? = null,
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
		if (agentRunId == null || !hasCompleteFrozenEnvelope(workspaceId, agentRunId, envelope)) {
			return RetryEligibilityDto(eligible = false, reason = "INCOMPLETE_ENVELOPE")
		}
		try {
			workspaceAccessService.requireWritable(workspaceId)
		} catch (_: Exception) {
			return RetryEligibilityDto(eligible = false, reason = "UNAUTHORIZED")
		}
		return RetryEligibilityDto(eligible = true, reason = null)
	}

	internal fun hasCompleteFrozenEnvelope(
		workspaceId: UUID,
		agentRunId: UUID,
		envelope: ChatExecutionEnvelopeRow?,
	): Boolean {
		if (
			envelope == null ||
			envelope.envelopeFingerprint.isBlank() ||
			envelope.sourceSnapshotId == null
		) return false
		val settings = runCatching { objectMapper.readTree(envelope.generationSettingsJson) }.getOrNull() ?: return false
		if (settings !is tools.jackson.databind.node.ObjectNode) return false
		val requiredSettings = setOf(
			"promptVersion",
			"toolPolicyVersion",
			"budgetSnapshot",
			"contentType",
			"contentBriefSnapshot",
		)
		if (requiredSettings.any { !settings.has(it) }) return false
		return sqlExecutor.queryForObject(
			"""
			select exists(
			  select 1
			  from content_source_snapshots snapshot
			  where snapshot.workspace_id = ? and snapshot.id = ?
			    and jsonb_typeof(snapshot.inputs_snapshot) = 'array'
			)
			and (
			  select count(*) from agent_run_inputs input
			  where input.workspace_id = ? and input.agent_run_id = ? and input.input_kind = 'SEED'
			) >= coalesce((
			  select jsonb_array_length(snapshot.inputs_snapshot)
			  from content_source_snapshots snapshot
			  where snapshot.workspace_id = ? and snapshot.id = ?
			), 0)
			and coalesce((
			  select bool_and(
			    entry.tool_name <> '' and jsonb_typeof(entry.normalized_arguments) = 'object'
			      and jsonb_typeof(entry.bounded_result) = 'object'
			  )
			  from chat_execution_transcript_entries entry
			  where entry.workspace_id = ? and entry.envelope_id = ?
				), true)
				and not exists (
				  select 1 from agent_run_sources source
				  where source.workspace_id = ? and source.agent_run_id = ?
				    and coalesce(nullif(trim(source.source_display_name), ''), '') = ''
				)
				""".trimIndent(),
			Boolean::class.java,
			workspaceId,
			envelope.sourceSnapshotId,
			workspaceId,
			agentRunId,
			workspaceId,
			envelope.sourceSnapshotId,
				workspaceId,
				envelope.id,
				workspaceId,
				agentRunId,
		) == true
	}

	private data class OrphanRun(
		val id: UUID,
		val instruction: String,
		val userId: UUID,
		val createdAt: Instant,
		val fingerprint: String,
		val promptVersion: String?,
		val toolPolicyVersion: String?,
		val budgetSnapshot: String?,
		val contentType: String?,
		val contentProfileRevisionId: UUID?,
		val contentBriefSnapshot: String?,
		val sourceSnapshotId: UUID?,
	)

	private fun projectLegacyRunsIntoTurns(workspaceId: UUID, sessionId: UUID) {
		val orphanRuns = sqlExecutor.query(
			"""
			select a.id, a.instruction_snapshot, a.created_by_user_id, a.created_at, a.request_fingerprint,
			       a.prompt_version, a.tool_policy_version, a.budget_snapshot::text as budget_snapshot,
			       a.content_type, a.content_profile_revision_id, a.content_brief_snapshot::text as content_brief_snapshot,
			       a.source_snapshot_id
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
					promptVersion = rs.getString("prompt_version"),
					toolPolicyVersion = rs.getString("tool_policy_version"),
					budgetSnapshot = rs.getString("budget_snapshot"),
					contentType = rs.getString("content_type"),
					contentProfileRevisionId = rs.getObject("content_profile_revision_id", UUID::class.java),
					contentBriefSnapshot = rs.getString("content_brief_snapshot"),
					sourceSnapshotId = rs.getObject("source_snapshot_id", UUID::class.java),
				)
			},
			workspaceId,
			sessionId,
		)
		for (orphan in orphanRuns) {
			val settingsJson = if (orphan.promptVersion != null) {
				objectMapper.writeValueAsString(
					mapOf(
						"promptVersion" to orphan.promptVersion,
						"toolPolicyVersion" to (orphan.toolPolicyVersion ?: "read-only-v1"),
						"budgetSnapshot" to (orphan.budgetSnapshot ?: objectMapper.writeValueAsString(properties.chatBudgetSnapshot())),
						"contentType" to (orphan.contentType ?: "CHANGELOG"),
						"contentProfileRevisionId" to orphan.contentProfileRevisionId,
						"contentBriefSnapshot" to orphan.contentBriefSnapshot,
					),
				)
			} else {
				"{}"
			}
			compatibilityWriter.recordDirectChatRun(
				workspaceId = workspaceId,
				userId = orphan.userId,
				chatId = sessionId,
				runId = orphan.id,
				instruction = orphan.instruction,
				fingerprint = orphan.fingerprint,
				settingsJson = settingsJson,
				sourceSnapshotId = orphan.sourceSnapshotId,
				now = orphan.createdAt,
			)
		}
	}

	internal fun toRunResponse(run: AgentRunRecord): ChatAgentRunResponse = with(run) {
		val brief = contentBriefSnapshotJson?.let { objectMapper.readValue(it, ContentBrief::class.java) }
		toChatResponse(
			artifact = agentRunQueryPersistence.findArtifactForAgentRun(workspaceId, id)?.let {
				ChatAgentArtifactSummaryResponse(it.id, it.status, it.title, contentType, it.updatedAt)
			},
			brief = brief,
		)
	}

}
