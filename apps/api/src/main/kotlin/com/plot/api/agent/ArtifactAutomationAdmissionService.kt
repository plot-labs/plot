package com.plot.api.agent

import com.plot.api.ai.provider.AgentResponseMode
import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.content.ContentSourceSnapshotService
import com.plot.api.content.ContentType
import com.plot.api.contentprofile.ContentProfileService
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.TransactionExecutor
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import com.plot.api.common.AfterCommit
import tools.jackson.databind.ObjectMapper

/** Admits background content automation without projecting it into user Chat history. */
@Service
class ArtifactAutomationAdmissionService(
	private val sqlExecutor: SqlExecutor,
	private val transactionExecutor: TransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val queries: AgentRunQueryPersistence,
	private val registration: AgentRunRegistrationPersistence,
	private val snapshots: AgentExecutionSnapshotPersistence,
	private val tools: ReadOnlyAgentTools,
	private val properties: AgentProperties,
	private val objectMapper: ObjectMapper,
	private val contentProfileService: ContentProfileService,
	private val contentSourceSnapshotService: ContentSourceSnapshotService,
	private val workspaceAccessService: WorkspaceAccessService,
	private val dispatcher: AgentRunDispatcher,
	private val sourceAdmission: AgentRunSourceAdmission,
) {
	fun admit(
		principal: WorkspacePrincipal,
		instruction: String,
		writingBlockIds: List<UUID>,
		idempotencyKey: String,
		title: String,
	): AgentRunRecord {
		workspaceAccessService.requireWritable(principal.workspaceId)
		val key = idempotencyKey.trim()
		require(key.isNotBlank() && key.length <= 200) { "Idempotency key is required" }
		val normalizedInstruction = instruction.trim()
		require(normalizedInstruction.isNotBlank()) { "Instruction is required" }
		require(writingBlockIds.distinct().size == writingBlockIds.size) { "Writing Block IDs must be unique" }
		val fingerprint = fingerprint(normalizedInstruction, writingBlockIds)

		return transactionExecutor.execute {
			sqlExecutor.queryForObject(
				"select pg_advisory_xact_lock(hashtextextended(?, 0))",
				{ _, _ -> Unit },
				"${principal.workspaceId}:automation:$key",
			)
			queries.findAgentRunByIdempotencyKey(
				principal.workspaceId,
				AgentRunOrigin.AUTOMATION,
				key,
				forUpdate = true,
			)?.let { existing ->
				if (existing.requestFingerprint != fingerprint) throw AgentRunIdempotencyConflictException()
				return@execute existing
			}

			val workspaceId = principal.workspaceId
			val now = Instant.now()
			val sources = sourceAdmission.lockActiveSources(workspaceId)
			val sessionId = uuidGenerator.next()
			sqlExecutor.update(
				"""
				insert into work_sessions (
				  id, workspace_id, title, status, created_by_user_id, latest_generation_run_id,
				  last_activity_at, created_at, updated_at, session_kind
				) values (?, ?, ?, 'OPEN', ?, null, ?, ?, ?, 'AUTOMATION')
				""".trimIndent(),
				sessionId,
				workspaceId,
				title.trim().takeIf { it.isNotBlank() } ?: "Content automation",
				principal.userId,
				Timestamp.from(now),
				Timestamp.from(now),
				Timestamp.from(now),
			)

			val runId = uuidGenerator.next()
			val profileRevisionId = contentProfileService.currentRevisionId(workspaceId)
			registration.insertRequired(
				NewAgentRun(
					id = runId,
					workspaceId = workspaceId,
					workSessionId = sessionId,
					createdByUserId = principal.userId,
					origin = AgentRunOrigin.AUTOMATION,
					idempotencyKey = key,
					requestFingerprint = fingerprint,
					instructionSnapshot = normalizedInstruction,
					promptVersion = "automation-agent-v1",
					toolPolicyVersion = "read-only-v1",
					budgetSnapshotJson = objectMapper.writeValueAsString(properties.chatBudgetSnapshot()),
					contentType = ContentType.ARTIFACT,
					contentProfileRevisionId = profileRevisionId,
					contentBriefSnapshotJson = null,
					maxAttempts = properties.maxAttempts,
				),
				now,
			)

			sourceAdmission.registerContextSources(workspaceId, runId, sources, now)
			writingBlockIds.forEachIndexed { index, blockId ->
				val sourceScopeId = sourceAdmission.findReadableBlockSource(workspaceId, blockId, sources)
					?: throw AgentToolAccessException("SOURCE_ITEM_NOT_READY")
				val input = tools.readWritingBlock(workspaceId, runId, sourceScopeId, blockId).adoptedInput
					?: throw AgentToolAccessException("SOURCE_ITEM_NOT_READY")
				registration.insertInput(
					workspaceId,
					runId,
					input.copy(
						inputKind = AgentRunInputKind.SEED,
						routineId = null,
						activitySequence = null,
						orderIndex = index,
						capturedAt = now,
					),
				)
			}

			val sourceSnapshot = contentSourceSnapshotService.findOrCreateSnapshotForAgentRun(workspaceId, runId)
			val settingsJson = objectMapper.writeValueAsString(
				mapOf(
					"promptVersion" to "automation-agent-v1",
					"toolPolicyVersion" to "read-only-v1",
					"budgetSnapshot" to objectMapper.writeValueAsString(properties.chatBudgetSnapshot()),
					"contentType" to ContentType.ARTIFACT.name,
					"contentProfileRevisionId" to profileRevisionId,
					"contentBriefSnapshot" to null,
					"responseMode" to AgentResponseMode.ARTIFACT_REQUIRED.name,
				),
			)
			snapshots.insertEnvelope(
				uuidGenerator.next(),
				workspaceId,
				runId,
				1,
				fingerprint,
				settingsJson,
				sourceSnapshot.id,
				now,
			)
			scheduleDispatchAfterCommit()
			requireNotNull(queries.findAgentRun(workspaceId, runId))
		}
	}

	private fun scheduleDispatchAfterCommit() {
		if (!properties.autoDispatchEnabled) return
		AfterCommit.runOrNow { dispatcher.dispatch() }
	}

	private fun fingerprint(instruction: String, writingBlockIds: List<UUID>): String {
		val canonical = buildString {
			append(instruction).append('|')
			writingBlockIds.forEach { append(it).append(',') }
		}
		return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
			.joinToString("") { byte -> "%02x".format(byte) }
	}
}
