package com.plot.api.routine

import com.plot.api.agent.NewAgentRun
import com.plot.api.agent.AgentRunRegistrationPersistence
import com.plot.api.agent.AgentRunInputKind
import com.plot.api.agent.AgentRunInputRequest
import com.plot.api.agent.AgentRunQueryPersistence
import com.plot.api.agent.AgentRunRecord
import com.plot.api.agent.AgentRunSourceRole

import com.plot.api.chat.ChatCompatibilityWriter
import com.plot.api.common.UuidGenerator
import com.plot.api.contentprofile.ContentProfilePersistence
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.TransactionExecutor
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class RoutineAgentAdmissionPersistence(
	private val sqlExecutor: SqlExecutor,
	private val transactionExecutor: TransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val queryPersistence: AgentRunQueryPersistence,
	private val registration: AgentRunRegistrationPersistence,
	private val contentProfilePersistence: ContentProfilePersistence,
	private val clock: Clock? = null,
	private val compatibilityWriter: ChatCompatibilityWriter,
	private val objectMapper: ObjectMapper = ObjectMapper(),
) {
	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	private data class RoutineCursor(val value: Long?, val enabled: Boolean, val releaseCadence: Boolean)
	private data class LockedSource(val id: UUID, val displayName: String, val status: String, val statusChangedAt: Instant)

	fun dispatch(
		workspaceId: UUID,
		executionId: UUID,
		request: RoutineAgentDispatchRequest,
		now: Instant = currentInstant(),
		workerId: String? = null,
	): AgentRunRecord = transactionExecutor.execute {
		val execution = findExecutionForUpdate(workspaceId, executionId)
			?: throw RoutineExecutionStateException("Routine execution was not found")
		if (execution.status != RoutineExecutionStatus.PROBING) {
			throw RoutineExecutionStateException("Only a probing execution may be dispatched")
		}
		if (workerId != null && execution.claimedBy != workerId) {
			throw RoutineExecutionStateException("Routine execution claim was lost")
		}
		val ownershipClause = if (workerId == null) "" else " and claimed_by = ?"
		val ownershipArgs: Array<Any> = workerId?.let { arrayOf<Any>(it) } ?: emptyArray()
		val currentRoutineCursor = findRoutineCursorForUpdate(workspaceId, execution.routineId)
			?: throw RoutineExecutionStateException("Routine was not found")
		if (!currentRoutineCursor.enabled && execution.triggerKind != RoutineExecutionTriggerKind.MANUAL) {
			throw RoutineExecutionStateException("Routine is disabled")
		}
		if (currentRoutineCursor.releaseCadence && execution.releaseRequestId == null) {
			throw RoutineExecutionStateException("Release Routine requires a verified release request")
		}
		if (execution.releaseRequestId == null && currentRoutineCursor.value != execution.activityCursorBefore) {
			throw RoutineExecutionStateException("Routine activity cursor is stale")
		}
		if (execution.releaseRequestId != null) validateReleaseEvidence(execution, request)
		val lockedSources = lockSourceScopes(
			workspaceId,
			request.sourceScopes.map { it.sourceScopeId },
		)
		validateDispatchRequest(execution, request, lockedSources)
		if (execution.triggerKind != RoutineExecutionTriggerKind.GITHUB && hasInFlightRoutineRun(execution)) {
			throw RoutineExecutionStateException("Routine has an in-flight Agent run")
		}
		if (hasReservedSeed(execution, request.inputs)) {
			throw RoutineExecutionStateException("Routine evidence is already reserved or consumed")
		}

		val workSessionId = uuidGenerator.next()
		val routineName = sqlExecutor.queryForObject(
			"select name from routines where workspace_id = ? and id = ?",
			String::class.java,
			workspaceId,
			execution.routineId,
		) ?: "Routine"
		sqlExecutor.update(
			"""
			insert into work_sessions (
			  id, workspace_id, title, status, created_by_user_id, latest_generation_run_id,
			  last_activity_at, created_at, updated_at, routine_execution_id, session_kind
			) values (?, ?, ?, 'OPEN', ?, null, ?, ?, ?, ?, 'ROUTINE')
			""".trimIndent(),
			workSessionId,
			workspaceId,
			"Routine: $routineName",
			execution.createdByUserId,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
			executionId,
		)

		val agentRunId = uuidGenerator.next()
		val profileRevisionId = request.contentProfileRevisionId
			?: contentProfilePersistence.findCurrentRevision(workspaceId)?.id
		registration.insertRequired(
			NewAgentRun(
				id = agentRunId,
				workspaceId = workspaceId,
				workSessionId = workSessionId,
				createdByUserId = execution.createdByUserId,
				origin = request.origin,
				idempotencyKey = request.idempotencyKey ?: "routine:$executionId",
				requestFingerprint = request.requestFingerprint ?: "routine:$executionId",
				instructionSnapshot = request.instructionSnapshot.trim(),
				promptVersion = request.promptVersion.trim(),
				toolPolicyVersion = request.toolPolicyVersion.trim(),
				budgetSnapshotJson = request.budgetSnapshotJson,
				contentType = request.contentType,
				contentProfileRevisionId = profileRevisionId,
				contentBriefSnapshotJson = request.contentBriefSnapshotJson,
				maxAttempts = request.maxAttempts,
				routineExecutionId = executionId,
				routineId = execution.routineId,
			),
			now,
		)

		request.sourceScopes.forEachIndexed { index, source ->
			val captured = lockedSources[source.sourceScopeId]
			registration.insertSource(
				workspaceId, agentRunId, source.sourceScopeId, captured?.displayName, source.role, index,
				captured?.status ?: source.capturedStatus,
				captured?.statusChangedAt ?: source.capturedStatusChangedAt, now,
			)
		}
		request.inputs.forEach { input -> registration.insertInput(workspaceId, agentRunId, input) }

		val settingsJson = objectMapper.writeValueAsString(
			mapOf(
				"promptVersion" to request.promptVersion.trim(),
				"toolPolicyVersion" to request.toolPolicyVersion.trim(),
				"budgetSnapshot" to request.budgetSnapshotJson,
				"contentType" to request.contentType.name,
				"contentProfileRevisionId" to profileRevisionId,
				"contentBriefSnapshot" to request.contentBriefSnapshotJson,
			),
		)
		compatibilityWriter.recordRoutineRun(
			workspaceId = workspaceId,
			userId = execution.createdByUserId,
			workSessionId = workSessionId,
			runId = agentRunId,
			instruction = request.instructionSnapshot.trim(),
			fingerprint = request.requestFingerprint ?: "routine:$executionId",
			settingsJson = settingsJson,
			now = now,
		)

		val finishedAt = now
		val sqlArgs: Array<Any> = (listOf<Any>(
			request.activityCursorAfter,
			Timestamp.from(now),
			Timestamp.from(finishedAt),
			Timestamp.from(now),
			workspaceId,
			executionId,
		) + ownershipArgs.asList()).toTypedArray()
		val executionUpdated = sqlExecutor.update(
			"""
			update routine_executions
			set status = 'DISPATCHED', activity_cursor_after = ?, refresh_completed_at = coalesce(refresh_completed_at, ?),
			    claimed_by = null, claimed_at = null, finished_at = ?,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and status = 'PROBING'
			$ownershipClause
			""".trimIndent(),
			*sqlArgs,
		)
		if (executionUpdated != 1) throw RoutineExecutionStateException("Routine execution transition was lost")
		requireNotNull(queryPersistence.findAgentRun(workspaceId, agentRunId))
	}

	private fun hasInFlightRoutineRun(execution: RoutineExecutionRecord): Boolean = sqlExecutor.queryForObject(
		"""
		select exists(
		  select 1
		  from agent_runs run
		  where run.workspace_id = ? and run.routine_id = ?
		    and run.status in ('QUEUED', 'RUNNING')
		)
		""".trimIndent(),
		Boolean::class.java,
		execution.workspaceId,
		execution.routineId,
	) == true

	private fun hasReservedSeed(
		execution: RoutineExecutionRecord,
		inputs: List<AgentRunInputRequest>,
	): Boolean = inputs.any { input ->
		sqlExecutor.queryForObject(
			"""
			select exists(
			  select 1
			  from agent_run_inputs seed
			  join agent_runs run
			    on run.workspace_id = seed.workspace_id and run.id = seed.agent_run_id
			  where seed.workspace_id = ? and seed.routine_id = ?
			    and seed.writing_block_id = ? and seed.activity_sequence = ?
			    and seed.input_kind = 'SEED'
			    and run.status in ('QUEUED', 'RUNNING', 'SUCCEEDED')
			)
			""".trimIndent(),
			Boolean::class.java,
			execution.workspaceId,
			execution.routineId,
			input.writingBlockId,
			requireNotNull(input.activitySequence),
		) == true
	}

	private fun findExecutionForUpdate(workspaceId: UUID, id: UUID): RoutineExecutionRecord? = sqlExecutor.query(
		selectExecutionSql + " where e.workspace_id = ? and e.id = ? for update",
		executionMapper,
		workspaceId,
		id,
	).firstOrNull()
	private fun findRoutineCursorForUpdate(workspaceId: UUID, routineId: UUID): RoutineCursor? = sqlExecutor.query(
		"select activity_cursor_sequence, enabled, cadence from routines where workspace_id = ? and id = ? for update",
		{ rs, _ -> RoutineCursor(
			rs.getObject(1, Long::class.javaObjectType), rs.getBoolean("enabled"),
			rs.getString("cadence") in setOf("ON_GIT_TAG", "ON_GITHUB_RELEASE"),
		) },
		workspaceId,
		routineId,
	).firstOrNull()
	private fun validateDispatchRequest(
		execution: RoutineExecutionRecord,
		request: RoutineAgentDispatchRequest,
		lockedSources: Map<UUID, LockedSource>,
	) {
		require(request.instructionSnapshot.isNotBlank()) { "Agent instruction snapshot is required" }
		require(request.promptVersion.isNotBlank()) { "Prompt version is required" }
		require(request.toolPolicyVersion.isNotBlank()) { "Tool policy version is required" }
		require(request.budgetSnapshotJson.isNotBlank()) { "Agent budget snapshot is required" }
		require(request.maxAttempts > 0) { "Agent maximum attempts must be positive" }
		require(request.sourceScopes.count { it.role == AgentRunSourceRole.TRIGGER } == 1) {
			"Exactly one trigger source is required"
		}
		val trigger = request.sourceScopes.single { it.role == AgentRunSourceRole.TRIGGER }
		require(trigger.sourceScopeId == execution.triggerSourceScopeId) {
			"Agent trigger source does not match the execution source"
		}
		require(lockedSources.values.all { it.status == "ACTIVE" }) {
			"Agent source scopes must be active"
		}
		require(request.sourceScopes.map { it.sourceScopeId }.distinct().size == request.sourceScopes.size) {
			"Agent source scopes must be unique"
		}
		val configuredContextSources = sqlExecutor.query(
			"""
			select source_scope_id
			from routine_context_sources
			where workspace_id = ? and routine_id = ?
			order by order_index, source_scope_id
			""".trimIndent(),
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			execution.workspaceId,
			execution.routineId,
		)
		val requestContextSources = request.sourceScopes
			.filter { it.role == AgentRunSourceRole.CONTEXT }
			.map { it.sourceScopeId }
		if (requestContextSources != configuredContextSources) {
			throw RoutineExecutionStateException("Agent context sources must match the Routine context source snapshot")
		}
		require(request.inputs.isNotEmpty()) { "An active execution requires seed input" }
		require(request.inputs.all { it.inputKind == AgentRunInputKind.SEED }) {
			"Dispatch accepts seed inputs only"
		}
		require(request.inputs.all { it.routineId == execution.routineId }) {
			"Seed inputs must belong to the execution Routine"
		}
		require(request.inputs.map { it.orderIndex }.sorted() == request.inputs.indices.toList()) {
			"Seed input order must be contiguous"
		}
		require(request.inputs.all { it.activitySequence != null }) {
			"Seed inputs require activity sequences"
		}
		// An exact release is a separate range job, not a repository activity batch.
		// Its complete bound evidence was verified above; it must not drop old inputs.
		val activityCursorBefore = if (execution.releaseRequestId != null) 0L else execution.activityCursorBefore ?: 0L
		require(request.activityCursorAfter > activityCursorBefore) {
			"Activity cursor must advance beyond the previous cursor"
		}

		require(request.inputs.all {
			val activitySequence = it.activitySequence!!
			activitySequence > activityCursorBefore && activitySequence <= request.activityCursorAfter
		}) {
			"Activity cursor must cover every seed input"
		}
	}

	private fun validateReleaseEvidence(execution: RoutineExecutionRecord, request: RoutineAgentDispatchRequest) {
		val bound = sqlExecutor.query(
				"""
				select block.id, block.activity_sequence, block.content_hash
				from github_release_draft_requests release
				join github_release_draft_evidence evidence
				  on evidence.workspace_id = release.workspace_id and evidence.request_id = release.id
				  and evidence.observation_id = release.observation_id
				join writing_blocks block
				  on block.workspace_id = evidence.workspace_id and block.id = evidence.writing_block_id
				where release.workspace_id = ? and release.id = ? and release.routine_id = ?
				  and release.status in ('RESOLVING', 'GENERATING') and release.claimed_by is not null
				  and release.base_sha is not null and release.head_sha is not null
				  and ? = 'github-release:' || release.id || ':attempt:' || release.generation_attempt
				  and block.status = 'ACTIVE'
				  and block.metadata->>'baseSha' = release.base_sha
				  and block.metadata->>'headSha' = release.head_sha
				order by evidence.order_index
				for share of block
				""".trimIndent(),
				{ row, _ ->
					Triple(row.getObject("id", UUID::class.java), row.getLong("activity_sequence"), row.getString("content_hash"))
				},
				execution.workspaceId, execution.releaseRequestId, execution.routineId, execution.triggerKey,
			)
		require(bound.isNotEmpty() && bound == request.inputs.map {
			Triple(it.writingBlockId, it.activitySequence, it.contentHash)
		}) { "Release inputs must match the complete verified range evidence" }
	}
	private fun lockSourceScopes(workspaceId: UUID, sourceScopeIds: List<UUID>): Map<UUID, LockedSource> {
		require(sourceScopeIds.isNotEmpty()) { "Agent source scopes are required" }
		val distinctIds = sourceScopeIds.distinct()
		val placeholders = distinctIds.joinToString(",") { "?" }
		val rows = sqlExecutor.query(
			"""
				select scope.id, scope.display_name, scope.status,
			       greatest(
			         scope.status_changed_at,
			         namespace.updated_at,
			         binding.updated_at,
			         connection.updated_at
			       ) as lifecycle_version_at
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
			where scope.workspace_id = ? and scope.id in ($placeholders)
			order by scope.id
			for update of scope, namespace, binding, connection
			""".trimIndent(),
				{ rs, _ ->
						LockedSource(
							id = requireNotNull(rs.getObject("id", UUID::class.java)),
							displayName = requireNotNull(rs.getString("display_name")),
							status = requireNotNull(rs.getString("status")),
						statusChangedAt = requireNotNull(rs.getTimestamp("lifecycle_version_at")).toInstant(),
				)
			},
			workspaceId,
			*distinctIds.toTypedArray(),
		)
		if (rows.size != distinctIds.size) {
			throw RoutineExecutionStateException("Every Agent source scope must exist in the execution workspace")
		}
		return rows.associateBy { it.id }
	}
}
