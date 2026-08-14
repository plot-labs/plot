package com.plot.api.artifact.run

import com.plot.api.common.UuidGenerator
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class ArtifactRunPersistence(
	private val jdbcTemplate: JdbcTemplate,
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
				jdbcTemplate.update(
					"update artifact_runs set status = 'QUEUED', error_code = null, finished_at = null, transition_version = transition_version + 1, updated_at = ? where workspace_id = ? and id = ?",
					Timestamp.from(now), workspaceId, existing.id,
				)
				return requireNotNull(findById(workspaceId, existing.id))
			}
			return existing
		}

		val ownerExists = jdbcTemplate.query(
			"select id from agent_runs where workspace_id = ? and id = ? and created_by_user_id = ? for update",
			{ rs, _ -> rs.getObject("id", UUID::class.java) },
			workspaceId, agentRunId, createdByUserId,
		).isNotEmpty()
		check(ownerExists) { "Artifact run owner AgentRun is unavailable" }
		jdbcTemplate.update(
			"""
			insert into artifact_runs (
			  id, workspace_id, agent_run_id, created_by_user_id, idempotency_key,
			  request_fingerprint, status, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
			""".trimIndent(),
			id, workspaceId, agentRunId, createdByUserId, key, fingerprint,
			Timestamp.from(now), Timestamp.from(now),
		)
		return requireNotNull(findById(workspaceId, id))
	}

	fun findByAgentRun(workspaceId: UUID, agentRunId: UUID, forUpdate: Boolean = false): ArtifactRunRecord? = jdbcTemplate.query(
		selectSql + " where workspace_id = ? and agent_run_id = ?" + if (forUpdate) " for update" else "",
		mapper,
		workspaceId, agentRunId,
	).firstOrNull()

	fun findById(workspaceId: UUID, id: UUID): ArtifactRunRecord? = jdbcTemplate.query(
		selectSql + " where workspace_id = ? and id = ?",
		mapper,
		workspaceId, id,
	).firstOrNull()

	fun findWorkflowStateByWorkflowRun(workspaceId: UUID, workflowRunId: UUID): ArtifactRunWorkflowState? = jdbcTemplate.query(
		workflowStateSql + " where workflow.workspace_id = ? and workflow.id = ? order by workflow.created_at desc limit 1",
		workflowStateMapper,
		workspaceId, workflowRunId,
	).firstOrNull()

	fun findWorkflowStateByAgentRun(workspaceId: UUID, agentRunId: UUID): ArtifactRunWorkflowState? = jdbcTemplate.query(
		workflowStateSql + " where artifact.workspace_id = ? and artifact.agent_run_id = ? order by workflow.created_at desc nulls last limit 1",
		workflowStateMapper,
		workspaceId, agentRunId,
	).firstOrNull()

	fun syncWorkflowState(
		workspaceId: UUID,
		workflowRunId: UUID,
		status: ArtifactRunStatus,
		errorCode: String?,
		now: Instant = clock.instant(),
	) {
		jdbcTemplate.update(
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
			status.name, errorCode, status.name, Timestamp.from(now), status.name, Timestamp.from(now),
			Timestamp.from(now), workspaceId, workflowRunId,
		)
	}

	private val mapper = { rs: java.sql.ResultSet, _: Int ->
		ArtifactRunRecord(
			id = rs.getObject("id", UUID::class.java),
			workspaceId = rs.getObject("workspace_id", UUID::class.java),
			agentRunId = rs.getObject("agent_run_id", UUID::class.java),
			createdByUserId = rs.getObject("created_by_user_id", UUID::class.java),
			idempotencyKey = rs.getString("idempotency_key"),
			requestFingerprint = rs.getString("request_fingerprint"),
			status = ArtifactRunStatus.valueOf(rs.getString("status")),
			errorCode = rs.getString("error_code"),
			transitionVersion = rs.getLong("transition_version"),
			startedAt = rs.getTimestamp("started_at")?.toInstant(),
			finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
			createdAt = rs.getTimestamp("created_at").toInstant(),
			updatedAt = rs.getTimestamp("updated_at").toInstant(),
		)
	}

	private val workflowStateMapper = { rs: java.sql.ResultSet, _: Int ->
		ArtifactRunWorkflowState(
			artifactRunId = rs.getObject("artifact_run_id", UUID::class.java),
			agentRunId = rs.getObject("agent_run_id", UUID::class.java),
			workflowRunId = rs.getObject("workflow_run_id", UUID::class.java),
			status = ArtifactRunStatus.valueOf(rs.getString("artifact_status")),
			errorCode = rs.getString("artifact_error_code"),
			materialized = rs.getBoolean("materialized"),
		)
	}

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
