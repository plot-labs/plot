package com.plot.api.routine

import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class AgentRunAdmissionPersistence(
	private val sqlExecutor: JooqSqlExecutor,
	private val transactionExecutor: JooqTransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val queryPersistence: AgentRunQueryPersistence,
	private val clock: Clock? = null,
) {
	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	private data class RoutineCursor(val value: Long?)
	private data class LockedSource(val id: UUID, val status: String, val statusChangedAt: Instant)

	fun dispatch(
		workspaceId: UUID,
		executionId: UUID,
		request: AgentRunDispatchRequest,
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
		if (currentRoutineCursor.value != execution.activityCursorBefore) {
			throw RoutineExecutionStateException("Routine activity cursor is stale")
		}
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
			  last_activity_at, created_at, updated_at, routine_execution_id
			) values (?, ?, ?, 'OPEN', ?, null, ?, ?, ?, ?)
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
		sqlExecutor.update(
			"""
			insert into agent_runs (
			  id, workspace_id, routine_execution_id, routine_id, work_session_id, created_by_user_id,
			  origin, idempotency_key, request_fingerprint,
			  instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot,
			  status, current_step, attempt_count, max_attempts, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'QUEUED', 0, 0, ?, ?, ?)
			""".trimIndent(),
			agentRunId,
			workspaceId,
			executionId,
			execution.routineId,
			workSessionId,
			execution.createdByUserId,
			request.origin.name,
			request.idempotencyKey ?: "routine:$executionId",
			request.requestFingerprint ?: "routine:$executionId",
			request.instructionSnapshot.trim(),
			request.promptVersion.trim(),
			request.toolPolicyVersion.trim(),
			request.budgetSnapshotJson,
			request.maxAttempts,
			Timestamp.from(now),
			Timestamp.from(now),
		)

		request.sourceScopes.forEachIndexed { index, source ->
			val captured = lockedSources[source.sourceScopeId]
			sqlExecutor.update(
				"""
				insert into agent_run_sources (
				  id, workspace_id, agent_run_id, source_scope_id, source_role, order_index,
				  captured_status, captured_status_changed_at, captured_at
				) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				uuidGenerator.next(),
				workspaceId,
				agentRunId,
				source.sourceScopeId,
				source.role.name,
				index,
				captured?.status ?: source.capturedStatus,
				Timestamp.from(captured?.statusChangedAt ?: source.capturedStatusChangedAt),
				Timestamp.from(now),
			)
		}

		request.inputs.forEach { input ->
			sqlExecutor.update(
				"""
				insert into agent_run_inputs (
				  id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
				  source_provider, source_kind, source_label,
				  input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
				  snapshot_excerpt, original_url, source_created_at, source_updated_at,
				  content_hash, captured_at
				) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				uuidGenerator.next(),
				workspaceId,
				agentRunId,
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
				input.sourceCreatedAt?.let(Timestamp::from),
				input.sourceUpdatedAt?.let(Timestamp::from),
				input.contentHash,
				Timestamp.from(input.capturedAt),
			)
		}

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

	fun appendInput(
		workspaceId: UUID,
		agentRunId: UUID,
		input: AgentRunInputRequest,
		now: Instant = currentInstant(),
	): AgentRunInputRecord = transactionExecutor.execute {
		require(input.orderIndex >= 0) { "Agent input order must be non-negative" }
		require(input.inputKind == AgentRunInputKind.TOOL_RESULT) {
			"Only tool-result inputs may be appended after dispatch"
		}
		require(input.routineId == null) { "Tool-result inputs must not claim seed ownership" }
		val id = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into agent_run_inputs (
			  id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
			  source_provider, source_kind, source_label,
			  input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
			  snapshot_excerpt, original_url, source_created_at, source_updated_at,
			  content_hash, captured_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			id,
			workspaceId,
			agentRunId,
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
			input.sourceCreatedAt?.let(Timestamp::from),
			input.sourceUpdatedAt?.let(Timestamp::from),
			input.contentHash,
			Timestamp.from(input.capturedAt),
		)
		requireNotNull(queryPersistence.findInput(workspaceId, agentRunId, id))
	}
	private fun findExecutionForUpdate(workspaceId: UUID, id: UUID): RoutineExecutionRecord? = sqlExecutor.query(
		selectExecutionSql + " where e.workspace_id = ? and e.id = ? for update",
		executionMapper,
		workspaceId,
		id,
	).firstOrNull()
	private fun findRoutineCursorForUpdate(workspaceId: UUID, routineId: UUID): RoutineCursor? = sqlExecutor.query(
		"select activity_cursor_sequence from routines where workspace_id = ? and id = ? for update",
		{ rs, _ -> RoutineCursor(rs.getObject(1, Long::class.javaObjectType)) },
		workspaceId,
		routineId,
	).firstOrNull()
	private fun validateDispatchRequest(
		execution: RoutineExecutionRecord,
		request: AgentRunDispatchRequest,
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
		val activityCursorBefore = execution.activityCursorBefore ?: 0L
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
	private fun lockSourceScopes(workspaceId: UUID, sourceScopeIds: List<UUID>): Map<UUID, LockedSource> {
		require(sourceScopeIds.isNotEmpty()) { "Agent source scopes are required" }
		val distinctIds = sourceScopeIds.distinct()
		val placeholders = distinctIds.joinToString(",") { "?" }
		val rows = sqlExecutor.query(
			"""
			select scope.id, scope.status,
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
