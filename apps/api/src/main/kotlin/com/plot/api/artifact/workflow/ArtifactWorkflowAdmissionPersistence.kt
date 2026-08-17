package com.plot.api.artifact.workflow
import com.plot.api.common.ApiException

import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.common.UuidGenerator
import com.plot.api.entitlement.TrialPolicy
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import com.plot.api.routine.AgentToolAccessException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import tools.jackson.databind.ObjectMapper

class ArtifactWorkflowAdmissionPersistence(
	private val sqlExecutor: JooqSqlExecutor,
	private val objectMapper: ObjectMapper,
	private val transactionExecutor: JooqTransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val artifactRunPersistence: ArtifactRunPersistence,
	private val queryPersistence: ArtifactWorkflowQueryPersistence,
	private val materializationPersistence: ArtifactWorkflowMaterializationPersistence,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun findIdempotentRun(
		workspaceId: UUID,
		createdByUserId: UUID,
		idempotencyKey: String,
		requestFingerprint: String,
	): ArtifactWorkflowState? {
		val existing = sqlExecutor.query(
			"select id, request_fingerprint from generation_runs where workspace_id = ? and created_by_user_id = ? and idempotency_key = ?",
			{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) to requireNotNull(rs.getString(2)) },
			workspaceId, createdByUserId, idempotencyKey,
		).firstOrNull() ?: return null
		if (existing.second != requestFingerprint) throw ArtifactWorkflowIdempotencyConflictException()
		return queryPersistence.loadState(workspaceId, existing.first)
	}

	fun createRun(reservation: ArtifactWorkflowRunReservation): ArtifactWorkflowState = transactionExecutor.execute {
		reservation.workSessionId?.let { sessionId ->
			val sessionExists = sqlExecutor.query(
				"select id from work_sessions where workspace_id = ? and id = ? for update",
				{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) },
				reservation.workspaceId,
				sessionId,
			).isNotEmpty()
			if (!sessionExists) {
				throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SESSION", "Work session is unavailable in this workspace")
			}
		}
		reservation.agentRunId?.let { agentRunId ->
			val workSessionId = reservation.workSessionId
				?: throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SESSION", "Routine Agent artifact workflow requires its Chat")
			val linkedAgent = sqlExecutor.query(
				"""
				select id
				from agent_runs
				where workspace_id = ? and id = ? and created_by_user_id = ? and work_session_id = ?
				for update
				""".trimIndent(),
				{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) },
				reservation.workspaceId,
				agentRunId,
				reservation.createdByUserId,
				workSessionId,
			).isNotEmpty()
			if (!linkedAgent) {
				throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_RUN", "Agent run is unavailable in this workspace")
			}
			val expectedSourceCount = sqlExecutor.queryForObject(
				"select count(*) from agent_run_sources where workspace_id = ? and agent_run_id = ?",
				Int::class.java,
				reservation.workspaceId,
				agentRunId,
			) ?: 0
			val sourceStatuses = sqlExecutor.query(
				"""
				select scope.status, namespace.status, binding.status, connection.status
				from agent_run_sources source
				join source_scopes scope
				  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
				join source_namespaces namespace
				  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
				 and namespace.provider = scope.provider
				join connection_namespace_bindings binding
				  on binding.workspace_id = namespace.workspace_id and binding.source_namespace_id = namespace.id
				 and binding.provider = namespace.provider and binding.status = 'ACTIVE'
				join connections connection
				  on connection.workspace_id = binding.workspace_id and connection.id = binding.connection_id
				 and connection.provider = binding.provider and connection.status = 'ACTIVE'
				where source.workspace_id = ? and source.agent_run_id = ?
				order by scope.id
				for update of scope, namespace, binding, connection
				""".trimIndent(),
				{ rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) },
				reservation.workspaceId,
				agentRunId,
			)
			if (
				expectedSourceCount == 0 ||
				sourceStatuses.size != expectedSourceCount ||
				sourceStatuses.flatten().any { it != "ACTIVE" }
			) {
				throw AgentToolAccessException("SOURCE_NOT_READY")
			}
		}
		val existing = sqlExecutor.query(
			"select id, request_fingerprint from generation_runs where workspace_id = ? and created_by_user_id = ? and idempotency_key = ?",
			{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) to requireNotNull(rs.getString(2)) },
			reservation.workspaceId, reservation.createdByUserId, reservation.idempotencyKey,
		).firstOrNull()
		if (existing != null) {
			if (existing.second != reservation.requestFingerprint) throw ArtifactWorkflowIdempotencyConflictException()
			return@execute queryPersistence.loadState(reservation.workspaceId, existing.first)
		}
		requireTrialArtifactWorkflowCapacity(reservation.workspaceId)
		val now = clock.instant()
		reservation.artifactRunId?.let { artifactRunId ->
			val agentRunId = requireNotNull(reservation.agentRunId) { "Artifact run requires an AgentRun" }
			artifactRunPersistence.admit(
				workspaceId = reservation.workspaceId,
				agentRunId = agentRunId,
				createdByUserId = reservation.createdByUserId,
				idempotencyKey = reservation.idempotencyKey,
				requestFingerprint = reservation.requestFingerprint,
				id = artifactRunId,
				now = now,
			)
		}
		val inserted = sqlExecutor.update(
			"""
			insert into generation_runs (
			 id, workspace_id, work_session_id, agent_run_id, artifact_run_id, source_scope_id, created_by_user_id, idempotency_key, request_fingerprint,
			 status, workflow_version, prompt_version, output_schema_version, budget_version, provider,
			 model_name, budget_snapshot, user_instruction, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', 'fixed-v1', 'changelog-v8', 'artifact-workflow-v5',
			 'budget-v1', ?, ?, ?::jsonb, ?, ?, ?)
			on conflict (workspace_id, created_by_user_id, idempotency_key) do nothing
			""".trimIndent(),
			reservation.state.runId, reservation.workspaceId, reservation.workSessionId, reservation.agentRunId, reservation.artifactRunId,
			reservation.sourceScopeId,
			reservation.createdByUserId, reservation.idempotencyKey, reservation.requestFingerprint,
			reservation.provider, reservation.modelName, reservation.budgetJson, reservation.state.instruction,
			Timestamp.from(now), Timestamp.from(now),
		)
		if (inserted == 0) {
			val raced = sqlExecutor.query(
				"select id, request_fingerprint from generation_runs where workspace_id = ? and created_by_user_id = ? and idempotency_key = ?",
			{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) to requireNotNull(rs.getString(2)) },
				reservation.workspaceId, reservation.createdByUserId, reservation.idempotencyKey,
			).single()
			if (raced.second != reservation.requestFingerprint) throw ArtifactWorkflowIdempotencyConflictException()
			return@execute queryPersistence.loadState(reservation.workspaceId, raced.first)
		}
		reservation.state.evidence.forEach { materializationPersistence.insertEvidence(reservation.workspaceId, it) }
		materializationPersistence.insertCheckpoint(reservation.workspaceId, reservation.state, "EVIDENCE_SET", now)
		reservation.workSessionId?.let { sessionId ->
			val updated = sqlExecutor.update(
				"update work_sessions set latest_generation_run_id = ?, last_activity_at = ?, updated_at = ? where workspace_id = ? and id = ?",
				reservation.state.runId,
				Timestamp.from(now),
				Timestamp.from(now),
				reservation.workspaceId,
				sessionId,
			)
			check(updated == 1) { "Work session link was lost" }
		}
		reservation.state
	}

	private fun requireTrialArtifactWorkflowCapacity(workspaceId: UUID) {
		val entitlement = sqlExecutor.query(
			"select plan, entitlement_status, access_mode from workspaces where id = ? for update",
			{ rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) },
			workspaceId,
		).singleOrNull()
			?: throw ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied")
		if (entitlement.third != "full") {
			throw ApiException(
				HttpStatus.FORBIDDEN,
				"WORKSPACE_READ_ONLY",
				"This workspace is read-only. Reactivate a subscription to make changes.",
			)
		}
		if (entitlement.first != "trial" || entitlement.second != "trialing") return
		val occupiedPackSlotCount = sqlExecutor.queryForObject(
			"""
			select
			  (select count(*) from content_packs where workspace_id = ?)
			  +
			  (
			    select count(*)
			    from generation_runs run
			    where run.workspace_id = ?
			      and run.status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			      and not exists (
			        select 1
			        from content_packs pack
			        where pack.workspace_id = run.workspace_id
			          and pack.generation_run_id = run.id
			      )
			  )
			""".trimIndent(),
			Long::class.java,
			workspaceId,
			workspaceId,
		) ?: 0
		if (occupiedPackSlotCount >= TrialPolicy.PACK_LIMIT) {
			throw ApiException(
				HttpStatus.FORBIDDEN,
				"TRIAL_PACK_LIMIT_REACHED",
				"The trial already has three completed or in-progress artifact drafts. Wait for a failure to release capacity or subscribe.",
			)
		}
	}
}
