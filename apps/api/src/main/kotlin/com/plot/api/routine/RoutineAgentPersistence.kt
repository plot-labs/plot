package com.plot.api.routine

import com.plot.api.common.UuidGenerator
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class RoutineAgentPersistence(
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
	private val uuidGenerator: UuidGenerator,
	private val agentRunPersistence: AgentRunPersistence,
	private val clock: Clock? = null,
) {
	fun createExecution(request: RoutineExecutionRequest): RoutineExecutionRecord = transactionTemplate.execute {
		val triggerKey = request.triggerKey.trim()
		val requestFingerprint = request.requestFingerprint.trim()
		validateExecutionRequest(request, triggerKey, requestFingerprint)
		val existing = findByTriggerKey(request.workspaceId, request.routineId, triggerKey)
		if (existing != null) {
			if (existing.requestFingerprint != requestFingerprint) {
				throw RoutineExecutionIdempotencyConflictException()
			}
			return@execute existing
		}

		val id = request.id ?: uuidGenerator.next()
		val now = currentInstant()
		val inserted = jdbcTemplate.update(
			"""
			insert into routine_executions (
			  id, workspace_id, routine_id, created_by_user_id, trigger_source_scope_id,
			  trigger_kind, trigger_key, request_fingerprint, trigger_delivery_id,
			  scheduled_for, refresh_from, refresh_to, refresh_continuation,
			  activity_cursor_before, status, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PROBING', ?, ?)
			on conflict (workspace_id, routine_id, trigger_key) do nothing
			""".trimIndent(),
			id,
			request.workspaceId,
			request.routineId,
			request.createdByUserId,
			request.triggerSourceScopeId,
			request.triggerKind.name,
			triggerKey,
			requestFingerprint,
			request.triggerDeliveryId,
			request.scheduledFor?.let(Timestamp::from),
			request.refreshFrom?.let(Timestamp::from),
			request.refreshTo?.let(Timestamp::from),
			request.refreshContinuationJson,
			request.activityCursorBefore,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		if (inserted == 0) {
			val raced = requireNotNull(findByTriggerKey(request.workspaceId, request.routineId, triggerKey))
			if (raced.requestFingerprint != requestFingerprint) {
				throw RoutineExecutionIdempotencyConflictException()
			}
			return@execute raced
		}
		requireNotNull(findExecution(request.workspaceId, id))
	}

	fun findExecution(workspaceId: UUID, id: UUID): RoutineExecutionRecord? = jdbcTemplate.query(
		selectExecutionSql + " where e.workspace_id = ? and e.id = ?",
		executionMapper,
		workspaceId,
		id,
	).firstOrNull()

	fun findByTriggerKey(workspaceId: UUID, routineId: UUID, triggerKey: String): RoutineExecutionRecord? = jdbcTemplate.query(
		selectExecutionSql + " where e.workspace_id = ? and e.routine_id = ? and e.trigger_key = ?",
		executionMapper,
		workspaceId,
		routineId,
		triggerKey,
	).firstOrNull()

	fun findExecutionSummary(workspaceId: UUID, executionId: UUID): RoutineExecutionSummaryRecord? = jdbcTemplate.query(
		"""
		select execution.id as execution_id, execution.status as execution_status,
		       execution.error_code as execution_error_code,
		       agent.work_session_id, agent.id as agent_run_id, agent.status as agent_run_status,
		       agent.failure_code as agent_failure_code,
		       handoff.generation_run_id as generation_run_id,
		       pack.id as artifact_id, execution.started_at,
		       case when agent.id is null then execution.finished_at else agent.finished_at end as finished_at
		from routine_executions execution
		left join agent_runs agent
		  on agent.workspace_id = execution.workspace_id
		 and agent.routine_execution_id = execution.id
		left join lateral (
		  select step.generation_run_id
		  from agent_steps step
		  where step.workspace_id = agent.workspace_id
		    and step.agent_run_id = agent.id
		    and step.generation_run_id is not null
		  order by step.sequence desc, step.id desc
		  limit 1
		) handoff on true
		left join content_packs pack
		  on pack.workspace_id = execution.workspace_id
		 and pack.generation_run_id = handoff.generation_run_id
		where execution.workspace_id = ? and execution.id = ?
		""".trimIndent(),
		{ rs, _ ->
			RoutineExecutionSummaryRecord(
				executionId = rs.getObject("execution_id", UUID::class.java),
				executionStatus = RoutineExecutionStatus.valueOf(rs.getString("execution_status")),
				executionErrorCode = rs.getString("execution_error_code"),
				workSessionId = rs.getObject("work_session_id", UUID::class.java),
				agentRunId = rs.getObject("agent_run_id", UUID::class.java),
				agentRunStatus = rs.getString("agent_run_status")?.let(AgentRunStatus::valueOf),
				agentFailureCode = rs.getString("agent_failure_code"),
				artifactWorkflowRunId = rs.getObject("generation_run_id", UUID::class.java),
				artifactId = rs.getObject("artifact_id", UUID::class.java),
				startedAt = rs.getTimestamp("started_at")?.toInstant(),
				finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
			)
		},
		workspaceId,
		executionId,
	).singleOrNull()

	fun claimNext(
		workerId: String,
		now: Instant = currentInstant(),
		staleBefore: Instant = now.minusSeconds(120),
	): RoutineExecutionRecord? = claim(
		workerId = workerId,
		now = now,
		staleBefore = staleBefore,
		where = "(e.next_attempt_at is null or e.next_attempt_at <= ?) and (e.claimed_by is null or e.claimed_at < ?)",
		args = arrayOf(Timestamp.from(now), Timestamp.from(staleBefore)),
	)

	fun claimById(
		workerId: String,
		workspaceId: UUID,
		executionId: UUID,
		now: Instant = currentInstant(),
		staleBefore: Instant = now.minusSeconds(120),
	): RoutineExecutionRecord? = claim(
		workerId = workerId,
		now = now,
		staleBefore = staleBefore,
		where = "e.workspace_id = ? and e.id = ? and (e.next_attempt_at is null or e.next_attempt_at <= ?) and (e.claimed_by is null or e.claimed_at < ?)",
		args = arrayOf(workspaceId, executionId, Timestamp.from(now), Timestamp.from(staleBefore)),
	)

	fun saveRefreshContinuation(
		workspaceId: UUID,
		executionId: UUID,
		workerId: String,
		continuationJson: String,
		now: Instant = currentInstant(),
	): RoutineExecutionRecord = transactionTemplate.execute {
		val updated = jdbcTemplate.update(
			"""
			update routine_executions
			set refresh_continuation = ?::jsonb, error_code = null, updated_at = ?
			where workspace_id = ? and id = ? and status = 'PROBING'
			  and claimed_by = ? and refresh_completed_at is null
			""".trimIndent(),
			continuationJson,
			Timestamp.from(now),
			workspaceId,
			executionId,
			workerId,
		)
		if (updated != 1) throw RoutineClaimLostException()
		requireNotNull(findExecution(workspaceId, executionId))
	}

	fun completeRefresh(
		workspaceId: UUID,
		executionId: UUID,
		workerId: String,
		now: Instant = currentInstant(),
	): RoutineExecutionRecord = transactionTemplate.execute {
		val updated = jdbcTemplate.update(
			"""
			update routine_executions
			set refresh_continuation = null, refresh_completed_at = ?, error_code = null, updated_at = ?
			where workspace_id = ? and id = ? and status = 'PROBING'
			  and claimed_by = ? and refresh_completed_at is null
			""".trimIndent(),
			Timestamp.from(now),
			Timestamp.from(now),
			workspaceId,
			executionId,
			workerId,
		)
		if (updated != 1) throw RoutineClaimLostException()
		requireNotNull(findExecution(workspaceId, executionId))
	}

	fun releaseExecutionForRetry(
		workspaceId: UUID,
		executionId: UUID,
		workerId: String,
		nextAttemptAt: Instant,
		errorCode: String? = null,
		now: Instant = currentInstant(),
	): RoutineExecutionRecord = transactionTemplate.execute {
		if (errorCode != null) require(errorCode.matches(SAFE_ERROR_CODE)) { "Routine refresh error code is invalid" }
		val updated = jdbcTemplate.update(
			"""
			update routine_executions
			set claimed_by = null, claimed_at = null, next_attempt_at = ?, error_code = ?,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and status = 'PROBING' and claimed_by = ?
			""".trimIndent(),
			Timestamp.from(nextAttemptAt),
			errorCode,
			Timestamp.from(now),
			workspaceId,
			executionId,
			workerId,
		)
		if (updated != 1) throw RoutineClaimLostException()
		requireNotNull(findExecution(workspaceId, executionId))
	}

	fun addEvidence(
		workspaceId: UUID,
		executionId: UUID,
		writingBlockIds: List<UUID>,
		now: Instant = currentInstant(),
	) {
		if (writingBlockIds.isEmpty()) return
		transactionTemplate.executeWithoutResult {
			writingBlockIds.distinct().forEachIndexed { index, writingBlockId ->
				jdbcTemplate.update(
					"""
					insert into routine_execution_evidence (
					  execution_id, workspace_id, writing_block_id, activity_sequence, order_index
					)
					select ?, block.workspace_id, block.id, block.activity_sequence, ?
					from writing_blocks block
					where block.workspace_id = ? and block.id = ?
					on conflict (execution_id, writing_block_id) do nothing
					""".trimIndent(),
					executionId,
					index,
					workspaceId,
					writingBlockId,
				)
			}
		}
	}

	fun listEvidence(workspaceId: UUID, executionId: UUID): List<RoutineExecutionEvidenceRecord> = jdbcTemplate.query(
		"""
		select execution_id, workspace_id, writing_block_id, activity_sequence, order_index
		from routine_execution_evidence
		where workspace_id = ? and execution_id = ?
		order by order_index, writing_block_id
		""".trimIndent(),
		{ rs, _ ->
			RoutineExecutionEvidenceRecord(
				executionId = rs.getObject("execution_id", UUID::class.java),
				workspaceId = rs.getObject("workspace_id", UUID::class.java),
				writingBlockId = rs.getObject("writing_block_id", UUID::class.java),
				activitySequence = rs.getLong("activity_sequence"),
				orderIndex = rs.getInt("order_index"),
			)
		},
		workspaceId,
		executionId,
	)

	fun addContextSource(
		workspaceId: UUID,
		routineId: UUID,
		sourceScopeId: UUID,
		orderIndex: Int,
		now: Instant = currentInstant(),
	): RoutineContextSourceRecord = transactionTemplate.execute {
		require(orderIndex >= 0) { "Context source order must be non-negative" }
		val id = uuidGenerator.next()
		jdbcTemplate.update(
			"""
			insert into routine_context_sources (id, workspace_id, routine_id, source_scope_id, order_index, created_at)
			values (?, ?, ?, ?, ?, ?)
			on conflict (workspace_id, routine_id, source_scope_id) do update set order_index = excluded.order_index
			""".trimIndent(),
			id,
			workspaceId,
			routineId,
			sourceScopeId,
			orderIndex,
			Timestamp.from(now),
		)
		jdbcTemplate.query(
			"""
			select id, workspace_id, routine_id, source_scope_id, order_index, created_at
			from routine_context_sources
			where workspace_id = ? and routine_id = ? and source_scope_id = ?
			""".trimIndent(),
			contextSourceMapper,
			workspaceId,
			routineId,
			sourceScopeId,
		).single()
	}

	fun listContextSources(workspaceId: UUID, routineId: UUID): List<RoutineContextSourceRecord> = jdbcTemplate.query(
		"""
		select id, workspace_id, routine_id, source_scope_id, order_index, created_at
		from routine_context_sources
		where workspace_id = ? and routine_id = ?
		order by order_index, id
		""".trimIndent(),
		contextSourceMapper,
		workspaceId,
		routineId,
	)

	fun markNoActivity(
		workspaceId: UUID,
		executionId: UUID,
		now: Instant = currentInstant(),
		workerId: String? = null,
	): RoutineExecutionRecord = transactionTemplate.execute {
		val ownershipClause = if (workerId == null) "" else " and claimed_by = ?"
		val ownershipArgs: Array<Any> = workerId?.let { arrayOf<Any>(it) } ?: emptyArray()
		val sqlArgs: Array<Any> = (listOf<Any>(
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
			workspaceId,
			executionId,
		) + ownershipArgs.asList()).toTypedArray()
		val updated = jdbcTemplate.update(
			"""
			update routine_executions
			set status = 'NO_ACTIVITY', refresh_completed_at = coalesce(refresh_completed_at, ?),
			    claimed_by = null, claimed_at = null, finished_at = ?,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and status = 'PROBING'
			$ownershipClause
			""".trimIndent(),
			*sqlArgs,
		)
		if (updated != 1) throw RoutineExecutionStateException("Routine execution is not probing")
		requireNotNull(findExecution(workspaceId, executionId))
	}

	fun failExecution(
		workspaceId: UUID,
		executionId: UUID,
		errorCode: String,
		now: Instant = currentInstant(),
		workerId: String? = null,
	): RoutineExecutionRecord = transactionTemplate.execute {
		require(errorCode.matches(SAFE_ERROR_CODE)) { "Routine execution error code is invalid" }
		val ownershipClause = if (workerId == null) "" else " and claimed_by = ?"
		val ownershipArgs: Array<Any> = workerId?.let { arrayOf<Any>(it) } ?: emptyArray()
		val sqlArgs: Array<Any> = (listOf<Any>(
			errorCode,
			Timestamp.from(now),
			Timestamp.from(now),
			workspaceId,
			executionId,
		) + ownershipArgs.asList()).toTypedArray()
		val updated = jdbcTemplate.update(
			"""
			update routine_executions
			set status = 'FAILED', error_code = ?, claimed_by = null, claimed_at = null,
			    finished_at = ?, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and status = 'PROBING'
			$ownershipClause
			""".trimIndent(),
			*sqlArgs,
		)
		if (updated != 1) throw RoutineExecutionStateException("Routine execution is not probing")
		requireNotNull(findExecution(workspaceId, executionId))
	}

	fun projectRoutine(
		workspaceId: UUID,
		routineId: UUID,
		executionId: UUID,
		now: Instant,
		nextRunAt: Instant,
		status: String,
		errorCode: String? = null,
		projectionAt: Instant = now,
	) {
		require(status in setOf("QUEUED", "NO_ACTIVITY", "FAILED")) { "Invalid Routine projection status" }
		val updated = jdbcTemplate.update(
			"""
			update routines
			set last_run_at = ?, next_run_at = ?, last_execution_id = ?, last_generation_run_id = null,
			    last_run_status = ?, last_error_code = ?,
			    active_execution_id = case when active_execution_id = ? then null else active_execution_id end,
			    claimed_by = case when active_execution_id = ? then null else claimed_by end,
			    claimed_at = case when active_execution_id = ? then null else claimed_at end,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ?
			  and (active_execution_id is null or active_execution_id = ?)
			  and (
			    last_execution_id = ?
			    or last_run_at is null
			    or last_run_at < ?
			    or (last_run_at = ? and (last_execution_id is null or last_execution_id < ?))
			  )
			""".trimIndent(),
			Timestamp.from(projectionAt),
			Timestamp.from(nextRunAt),
			executionId,
			status,
			errorCode,
			executionId,
			executionId,
			executionId,
			Timestamp.from(now),
			workspaceId,
			routineId,
			executionId,
			executionId,
			Timestamp.from(projectionAt),
			Timestamp.from(projectionAt),
			executionId,
		)
		// A newer execution may already own the public Routine projection. The
		// canonical execution remains terminal; preserving the newer projection
		// is the intended result, not a claim failure.
	}

	private fun claim(
		workerId: String,
		now: Instant,
		staleBefore: Instant,
		where: String,
		args: Array<Any>,
	): RoutineExecutionRecord? = transactionTemplate.execute {
			val candidate = jdbcTemplate.query(
				selectExecutionSql + "\nwhere e.status = 'PROBING' and $where order by e.created_at, e.id for update skip locked limit 1",
				executionMapper,
				*args,
			).firstOrNull() ?: return@execute null
			val updated = jdbcTemplate.update(
				"""
				update routine_executions
				set claimed_by = ?, claimed_at = ?, attempt_count = attempt_count + 1,
				    started_at = coalesce(started_at, ?), next_attempt_at = null,
				    transition_version = transition_version + 1, updated_at = ?
				where workspace_id = ? and id = ? and transition_version = ?
				  and status = 'PROBING'
				  and (claimed_by is null or claimed_at < ?)
				""".trimIndent(),
				workerId,
				Timestamp.from(now),
				Timestamp.from(now),
				Timestamp.from(now),
				candidate.workspaceId,
				candidate.id,
				candidate.transitionVersion,
				Timestamp.from(staleBefore),
			)
			if (updated != 1) null else findExecution(candidate.workspaceId, candidate.id)
		}


	fun dispatch(
		workspaceId: UUID,
		executionId: UUID,
		request: AgentRunDispatchRequest,
		now: Instant = currentInstant(),
		workerId: String? = null,
	): AgentRunRecord = agentRunPersistence.dispatch(workspaceId, executionId, request, now, workerId)

	fun findAgentRun(workspaceId: UUID, id: UUID): AgentRunRecord? =
		agentRunPersistence.findAgentRun(workspaceId, id)

	fun listAgentRunSources(workspaceId: UUID, agentRunId: UUID): List<AgentRunSourceRecord> =
		agentRunPersistence.listAgentRunSources(workspaceId, agentRunId)

	fun listAgentRunInputs(workspaceId: UUID, agentRunId: UUID): List<AgentRunInputRecord> =
		agentRunPersistence.listAgentRunInputs(workspaceId, agentRunId)

	fun appendInput(
		workspaceId: UUID,
		agentRunId: UUID,
		input: AgentRunInputRequest,
		now: Instant = currentInstant(),
	): AgentRunInputRecord = agentRunPersistence.appendInput(workspaceId, agentRunId, input, now)

	fun appendStep(
		workspaceId: UUID,
		request: AgentStepRequest,
		now: Instant = currentInstant(),
	): AgentStepRecord = agentRunPersistence.appendStep(workspaceId, request, now)

	fun listSteps(workspaceId: UUID, agentRunId: UUID): List<AgentStepRecord> =
		agentRunPersistence.listSteps(workspaceId, agentRunId)

	fun findArtifactId(workspaceId: UUID, artifactWorkflowRunId: UUID): UUID? =
		agentRunPersistence.findArtifactId(workspaceId, artifactWorkflowRunId)

	fun claimNextAgentRun(
		workerId: String,
		now: Instant = currentInstant(),
		staleBefore: Instant,
	): ClaimedAgentRun? = agentRunPersistence.claimNextAgentRun(workerId, now, staleBefore)

	fun recordAgentInfrastructureFailure(
		claim: ClaimedAgentRun,
		now: Instant = currentInstant(),
	) {
		agentRunPersistence.recordAgentInfrastructureFailure(claim, now)
	}

	fun beginModelDecision(claim: ClaimedAgentRun, maxModelCalls: Int): AgentRunRecord =
		agentRunPersistence.beginModelDecision(claim, maxModelCalls)

	fun reserveStep(
		claim: ClaimedAgentRun,
		request: AgentStepRequest,
		maxToolCalls: Int,
		now: Instant = currentInstant(),
	): AgentStepRecord = agentRunPersistence.reserveStep(claim, request, maxToolCalls, now)

	fun completeToolStep(
		claim: ClaimedAgentRun,
		stepId: UUID,
		resultJson: String,
		adoptedInput: AgentRunInputRequest? = null,
		sourceScopeId: UUID? = null,
		sourceStatusChangedAt: Instant? = null,
		maxEvidenceCharacters: Int,
		now: Instant = currentInstant(),
	): AgentStepRecord = agentRunPersistence.completeToolStep(
		claim,
		stepId,
		resultJson,
		adoptedInput,
		sourceScopeId,
		sourceStatusChangedAt,
		maxEvidenceCharacters,
		now,
	)

	fun linkArtifactWorkflowStep(
		claim: ClaimedAgentRun,
		stepId: UUID,
		artifactWorkflowRunId: UUID,
		resultJson: String,
		nextAttemptAt: Instant,
		now: Instant = currentInstant(),
	): AgentStepRecord = agentRunPersistence.linkArtifactWorkflowStep(
		claim,
		stepId,
		artifactWorkflowRunId,
		resultJson,
		nextAttemptAt,
		now,
	)

	fun releaseAgentClaim(claim: ClaimedAgentRun, nextAttemptAt: Instant, now: Instant = currentInstant()) {
		agentRunPersistence.releaseAgentClaim(claim, nextAttemptAt, now)
	}

	fun scheduleAgentRetry(
		claim: ClaimedAgentRun,
		errorCode: String,
		nextAttemptAt: Instant,
		now: Instant = currentInstant(),
	): AgentRunRecord = agentRunPersistence.scheduleAgentRetry(claim, errorCode, nextAttemptAt, now)

	fun failAgentRun(
		claim: ClaimedAgentRun,
		errorCode: String,
		now: Instant = currentInstant(),
	): AgentRunRecord = agentRunPersistence.failAgentRun(claim, errorCode, now)

	fun succeedAgentRun(claim: ClaimedAgentRun, now: Instant = currentInstant()): AgentRunRecord =
		agentRunPersistence.succeedAgentRun(claim, now)

	fun loadArtifactWorkflowState(workspaceId: UUID, artifactWorkflowRunId: UUID): AgentArtifactWorkflowState? =
		agentRunPersistence.loadArtifactWorkflowState(workspaceId, artifactWorkflowRunId)

	fun allAgentSourcesActive(workspaceId: UUID, agentRunId: UUID): Boolean =
		agentRunPersistence.allAgentSourcesActive(workspaceId, agentRunId)

	fun findRunningStep(workspaceId: UUID, agentRunId: UUID, sequence: Int): AgentStepRecord? =
		agentRunPersistence.findRunningStep(workspaceId, agentRunId, sequence)

	private fun validateExecutionRequest(
		request: RoutineExecutionRequest,
		triggerKey: String,
		requestFingerprint: String,
	) {
		require(triggerKey.isNotBlank()) { "Routine execution trigger key is required" }
		require(requestFingerprint.isNotBlank()) { "Routine execution fingerprint is required" }
		if (request.triggerKind == RoutineExecutionTriggerKind.GITHUB) {
			requireNotNull(request.triggerDeliveryId) { "GitHub execution requires a delivery identity" }
		} else {
			require(request.triggerDeliveryId == null) { "Only GitHub execution may carry a delivery identity" }
		}
	}

	private val executionMapper = { rs: ResultSet, _: Int -> rs.toRoutineExecution() }
	private val contextSourceMapper = { rs: ResultSet, _: Int -> rs.toContextSource() }

	private companion object {
		val SAFE_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,99}")
	}

	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	private val selectExecutionSql = """
		select e.id, e.workspace_id, e.routine_id, e.created_by_user_id, e.trigger_source_scope_id,
		       e.trigger_kind, e.trigger_key, e.request_fingerprint, e.trigger_delivery_id,
		       e.scheduled_for, e.refresh_from, e.refresh_to, e.refresh_continuation::text,
		       e.refresh_completed_at, e.activity_cursor_before, e.activity_cursor_after,
		       e.status, e.attempt_count, e.transition_version, e.claimed_by, e.claimed_at,
		       e.next_attempt_at, e.error_code, e.started_at, e.finished_at, e.created_at, e.updated_at
		from routine_executions e
	""".trimIndent()

	private fun ResultSet.toRoutineExecution() = RoutineExecutionRecord(
		id = getObject("id", UUID::class.java),
		workspaceId = getObject("workspace_id", UUID::class.java),
		routineId = getObject("routine_id", UUID::class.java),
		createdByUserId = getObject("created_by_user_id", UUID::class.java),
		triggerSourceScopeId = getObject("trigger_source_scope_id", UUID::class.java),
		triggerKind = RoutineExecutionTriggerKind.valueOf(getString("trigger_kind")),
		triggerKey = getString("trigger_key"),
		requestFingerprint = getString("request_fingerprint"),
		triggerDeliveryId = getObject("trigger_delivery_id", UUID::class.java),
		scheduledFor = getTimestamp("scheduled_for")?.toInstant(),
		refreshFrom = getTimestamp("refresh_from")?.toInstant(),
		refreshTo = getTimestamp("refresh_to")?.toInstant(),
		refreshContinuationJson = getString("refresh_continuation"),
		refreshCompletedAt = getTimestamp("refresh_completed_at")?.toInstant(),
		activityCursorBefore = getObject("activity_cursor_before", Long::class.javaObjectType),
		activityCursorAfter = getObject("activity_cursor_after", Long::class.javaObjectType),
		status = RoutineExecutionStatus.valueOf(getString("status")),
		attemptCount = getInt("attempt_count"),
		transitionVersion = getLong("transition_version"),
		claimedBy = getString("claimed_by"),
		claimedAt = getTimestamp("claimed_at")?.toInstant(),
		nextAttemptAt = getTimestamp("next_attempt_at")?.toInstant(),
		errorCode = getString("error_code"),
		startedAt = getTimestamp("started_at")?.toInstant(),
		finishedAt = getTimestamp("finished_at")?.toInstant(),
		createdAt = getTimestamp("created_at").toInstant(),
		updatedAt = getTimestamp("updated_at").toInstant(),
	)

	private fun ResultSet.toContextSource() = RoutineContextSourceRecord(
		id = getObject("id", UUID::class.java),
		workspaceId = getObject("workspace_id", UUID::class.java),
		routineId = getObject("routine_id", UUID::class.java),
		sourceScopeId = getObject("source_scope_id", UUID::class.java),
		orderIndex = getInt("order_index"),
		createdAt = getTimestamp("created_at").toInstant(),
	)

}
