package com.plot.api.artifact.run

import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.SqlRow
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class ArtifactRunPersistence(
	private val sql: SqlExecutor,
	private val uuidGenerator: UuidGenerator,
	private val clock: Clock = Clock.systemUTC(),
) {
	/** The caller owns the surrounding transaction. */
	fun admit(
		workspaceId: UUID,
		agentRunId: UUID,
		createdByUserId: UUID,
		idempotencyKey: String,
		requestFingerprint: String,
		id: UUID = uuidGenerator.next(),
		now: Instant = clock.instant(),
	): ArtifactRunRecord {
		val key = idempotencyKey.trim()
		val fingerprint = requestFingerprint.trim()
		require(key.isNotBlank()) { "Artifact run idempotency key is required" }
		require(fingerprint.isNotBlank()) { "Artifact run fingerprint is required" }
		val existing = findByAgentRun(workspaceId, agentRunId, forUpdate = true)
		if (existing != null) {
			if (existing.requestFingerprint != fingerprint) {
				throw ArtifactRunIdempotencyConflictException()
			}
			if (existing.status == ArtifactRunStatus.FAILED) {
				execute(
					"update artifact_runs set status = 'QUEUED', error_code = null, finished_at = null, transition_version = transition_version + 1, updated_at = ? where workspace_id = ? and id = ?",
					Timestamp.from(now),
					workspaceId,
					existing.id,
				)
				return requireNotNull(findById(workspaceId, existing.id))
			}
			return existing
		}

		val ownerExists = fetchRows(
			"select id from agent_runs where workspace_id = ? and id = ? and created_by_user_id = ? for update",
			workspaceId,
			agentRunId,
			createdByUserId,
		).isNotEmpty()
		check(ownerExists) { "Artifact run owner AgentRun is unavailable" }
		execute(
			"""
			insert into artifact_runs (
			  id, workspace_id, agent_run_id, created_by_user_id, idempotency_key,
			  request_fingerprint, status, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
			""".trimIndent(),
			id,
			workspaceId,
			agentRunId,
			createdByUserId,
			key,
			fingerprint,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		return requireNotNull(findById(workspaceId, id))
	}

	fun findByAgentRun(workspaceId: UUID, agentRunId: UUID, forUpdate: Boolean = false): ArtifactRunRecord? = fetchRows(
		selectSql + " where workspace_id = ? and agent_run_id = ?" + if (forUpdate) " for update" else "",
		workspaceId,
		agentRunId,
	).firstOrNull()?.toArtifactRun()

	fun findById(workspaceId: UUID, id: UUID): ArtifactRunRecord? = fetchRows(
		selectSql + " where workspace_id = ? and id = ?",
		workspaceId,
		id,
	).firstOrNull()?.toArtifactRun()

	fun findWorkflowStateByWorkflowRun(workspaceId: UUID, workflowRunId: UUID): ArtifactRunWorkflowState? = fetchRows(
		workflowStateSql + " where workflow.workspace_id = ? and workflow.id = ? order by workflow.created_at desc limit 1",
		workspaceId,
		workflowRunId,
	).firstOrNull()?.toWorkflowState()

	fun findWorkflowStateByAgentRun(workspaceId: UUID, agentRunId: UUID): ArtifactRunWorkflowState? = fetchRows(
		workflowStateSql + " where artifact.workspace_id = ? and artifact.agent_run_id = ? order by workflow.created_at desc nulls last limit 1",
		workspaceId,
		agentRunId,
	).firstOrNull()?.toWorkflowState()

	fun syncWorkflowState(
		workspaceId: UUID,
		workflowRunId: UUID,
		status: ArtifactRunStatus,
		errorCode: String?,
		now: Instant = clock.instant(),
	) {
		execute(
			"""
			update artifact_runs artifact
			set status = ?, error_code = ?,
			    started_at = case when ? in ('WRITING', 'REVIEWING', 'REWRITING', 'READY', 'NEEDS_REVIEW', 'FAILED') then coalesce(artifact.started_at, ?) else artifact.started_at end,
			    finished_at = case when ? in ('READY', 'NEEDS_REVIEW', 'FAILED') then ? else artifact.finished_at end,
			    transition_version = artifact.transition_version + 1, updated_at = ?
			where artifact.workspace_id = ?
			  and exists (
			    select 1 from generation_runs workflow
			    where workflow.workspace_id = artifact.workspace_id
			      and workflow.id = ?
			      and workflow.artifact_run_id = artifact.id
			  )
			""".trimIndent(),
			status.name,
			errorCode,
			status.name,
			Timestamp.from(now),
			status.name,
			Timestamp.from(now),
			Timestamp.from(now),
			workspaceId,
			workflowRunId,
		)
	}

	private fun SqlRow.toArtifactRun() = ArtifactRunRecord(
		id = requireNotNull(getObject("id", UUID::class.java)),
		workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
		agentRunId = requireNotNull(getObject("agent_run_id", UUID::class.java)),
		createdByUserId = requireNotNull(getObject("created_by_user_id", UUID::class.java)),
		idempotencyKey = requireNotNull(getString("idempotency_key")),
		requestFingerprint = requireNotNull(getString("request_fingerprint")),
		status = ArtifactRunStatus.valueOf(requireNotNull(getString("status"))),
		errorCode = getString("error_code"),
		transitionVersion = getLong("transition_version"),
		startedAt = getTimestamp("started_at")?.toInstant(),
		finishedAt = getTimestamp("finished_at")?.toInstant(),
		createdAt = requireNotNull(getTimestamp("created_at")).toInstant(),
		updatedAt = requireNotNull(getTimestamp("updated_at")).toInstant(),
	)

	private fun SqlRow.toWorkflowState() = ArtifactRunWorkflowState(
		artifactRunId = requireNotNull(getObject("artifact_run_id", UUID::class.java)),
		agentRunId = requireNotNull(getObject("agent_run_id", UUID::class.java)),
		workflowRunId = getObject("workflow_run_id", UUID::class.java),
		status = ArtifactRunStatus.valueOf(requireNotNull(getString("artifact_status"))),
		errorCode = getString("artifact_error_code"),
		materialized = getBoolean("materialized"),
	)

	private fun fetchRows(statement: String, vararg bindings: Any?): List<SqlRow> = sql.query(statement, *bindings)

	private fun execute(statement: String, vararg bindings: Any?): Int = sql.update(statement, *bindings)

	private val selectSql = """
		select id, workspace_id, agent_run_id, created_by_user_id, idempotency_key,
		       request_fingerprint, status, error_code, transition_version,
		       started_at, finished_at, created_at, updated_at
		from artifact_runs
	""".trimIndent()

	private val workflowStateSql = """
		select artifact.id as artifact_run_id,
		       artifact.agent_run_id,
		       workflow.id as workflow_run_id,
		       artifact.status as artifact_status,
		       artifact.error_code as artifact_error_code,
		       exists (
		         select 1 from content_packs pack
		         where pack.workspace_id = workflow.workspace_id
		           and pack.generation_run_id = workflow.id
		       ) as materialized
		from artifact_runs artifact
		left join generation_runs workflow
		  on workflow.workspace_id = artifact.workspace_id
		 and workflow.artifact_run_id = artifact.id
	""".trimIndent()
}
