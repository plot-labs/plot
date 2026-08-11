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
class AgentRunPersistence(
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
	private val uuidGenerator: UuidGenerator,
	private val clock: Clock? = null,
) {
	fun dispatch(
		workspaceId: UUID,
		executionId: UUID,
		request: AgentRunDispatchRequest,
		now: Instant = currentInstant(),
		workerId: String? = null,
	): AgentRunRecord = transactionTemplate.execute {
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

		val workSessionId = uuidGenerator.next()
		val routineName = jdbcTemplate.queryForObject(
			"select name from routines where workspace_id = ? and id = ?",
			String::class.java,
			workspaceId,
			execution.routineId,
		) ?: "Routine"
		jdbcTemplate.update(
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
		jdbcTemplate.update(
			"""
			insert into agent_runs (
			  id, workspace_id, routine_execution_id, routine_id, work_session_id, created_by_user_id,
			  instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot,
			  status, current_step, attempt_count, max_attempts, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'QUEUED', 0, 0, ?, ?, ?)
			""".trimIndent(),
			agentRunId,
			workspaceId,
			executionId,
			execution.routineId,
			workSessionId,
			execution.createdByUserId,
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
			jdbcTemplate.update(
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
			jdbcTemplate.update(
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
		val executionUpdated = jdbcTemplate.update(
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
		if (execution.triggerKind != RoutineExecutionTriggerKind.GITHUB) {
			val cursorUpdated = jdbcTemplate.update(
				"""
				update routines
				set activity_cursor_sequence = greatest(coalesce(activity_cursor_sequence, 0), ?), updated_at = ?
				where workspace_id = ? and id = ?
				""".trimIndent(),
				request.activityCursorAfter,
				Timestamp.from(now),
				workspaceId,
				execution.routineId,
			)
			if (cursorUpdated != 1) throw RoutineExecutionStateException("Routine cursor update was lost")
		}
		requireNotNull(findAgentRun(workspaceId, agentRunId))
	}

	fun findAgentRun(workspaceId: UUID, id: UUID): AgentRunRecord? = jdbcTemplate.query(
		selectAgentRunSql + " where a.workspace_id = ? and a.id = ?",
		agentRunMapper,
		workspaceId,
		id,
	).firstOrNull()

	fun listAgentRunSources(workspaceId: UUID, agentRunId: UUID): List<AgentRunSourceRecord> = jdbcTemplate.query(
		"""
		select id, workspace_id, agent_run_id, source_scope_id, source_role, order_index,
		       captured_status, captured_status_changed_at, captured_at
		from agent_run_sources
		where workspace_id = ? and agent_run_id = ?
		order by order_index, id
		""".trimIndent(),
		agentRunSourceMapper,
		workspaceId,
		agentRunId,
	)

	fun listAgentRunInputs(workspaceId: UUID, agentRunId: UUID): List<AgentRunInputRecord> = jdbcTemplate.query(
		"""
		select id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
		       source_provider, source_kind, source_label,
		       input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
		       snapshot_excerpt, original_url, source_created_at, source_updated_at,
		       content_hash, captured_at
		from agent_run_inputs
		where workspace_id = ? and agent_run_id = ?
		order by order_index, id
		""".trimIndent(),
		agentRunInputMapper,
		workspaceId,
		agentRunId,
	)

	fun appendInput(
		workspaceId: UUID,
		agentRunId: UUID,
		input: AgentRunInputRequest,
		now: Instant = currentInstant(),
	): AgentRunInputRecord = transactionTemplate.execute {
		require(input.orderIndex >= 0) { "Agent input order must be non-negative" }
		require(input.inputKind == AgentRunInputKind.TOOL_RESULT) {
			"Only tool-result inputs may be appended after dispatch"
		}
		require(input.routineId == null) { "Tool-result inputs must not claim seed ownership" }
		val id = uuidGenerator.next()
		jdbcTemplate.update(
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
		requireNotNull(findInput(workspaceId, agentRunId, id))
	}

	fun appendStep(
		workspaceId: UUID,
		request: AgentStepRequest,
		now: Instant = currentInstant(),
	): AgentStepRecord = transactionTemplate.execute {
		val id = uuidGenerator.next()
		jdbcTemplate.update(
			"""
			insert into agent_steps (
			  id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
			  tool_name, arguments, result, adopted_input_id, generation_run_id,
			  failure_code, started_at, finished_at, created_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			id,
			workspaceId,
			request.agentRunId,
			request.sequence,
			request.kind.name,
			request.status.name,
			request.idempotencyKey.trim(),
			request.toolName,
			request.argumentsJson,
			request.resultJson,
			request.adoptedInputId,
			request.generationRunId,
			request.failureCode,
			request.startedAt?.let(Timestamp::from),
			request.finishedAt?.let(Timestamp::from),
			Timestamp.from(now),
		)
		findStep(workspaceId, request.agentRunId, id)
	} ?: error("Agent step transaction returned no record")

	fun listSteps(workspaceId: UUID, agentRunId: UUID): List<AgentStepRecord> = jdbcTemplate.query(
		"""
		select id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
		       tool_name, arguments::text, result::text, adopted_input_id, generation_run_id,
		       failure_code, started_at, finished_at, created_at
		from agent_steps
		where workspace_id = ? and agent_run_id = ?
		order by sequence, id
		""".trimIndent(),
		agentStepMapper,
		workspaceId,
		agentRunId,
	)

	fun findArtifactId(workspaceId: UUID, generationRunId: UUID): UUID? = jdbcTemplate.query(
		"""
		select id
		from content_packs
		where workspace_id = ? and generation_run_id = ?
		""".trimIndent(),
		{ rs, _ -> rs.getObject("id", UUID::class.java) },
		workspaceId,
		generationRunId,
	).singleOrNull()

	fun claimNextAgentRun(
		workerId: String,
		now: Instant = currentInstant(),
		staleBefore: Instant,
	): ClaimedAgentRun? = transactionTemplate.execute {
		failExhaustedStaleAgentRuns(staleBefore, now)
		val candidate = jdbcTemplate.query(
			"""
			select workspace_id, id, transition_version
			from agent_runs
			where status in ('QUEUED', 'RUNNING')
			  and attempt_count < max_attempts
			  and (next_attempt_at is null or next_attempt_at <= ?)
			  and (claimed_by is null or claimed_at < ?)
			order by created_at, id
			for update skip locked
			limit 1
			""".trimIndent(),
			{ rs, _ -> Triple(
				rs.getObject("workspace_id", UUID::class.java),
				rs.getObject("id", UUID::class.java),
				rs.getLong("transition_version"),
			) },
			Timestamp.from(now),
			Timestamp.from(staleBefore),
		).firstOrNull() ?: return@execute null
		val updated = jdbcTemplate.update(
			"""
			update agent_runs
			set status = 'RUNNING', claimed_by = ?, claimed_at = ?, next_attempt_at = null,
			    started_at = coalesce(started_at, ?), transition_version = transition_version + 1,
			    updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ?
			  and status in ('QUEUED', 'RUNNING') and attempt_count < max_attempts
			  and (claimed_by is null or claimed_at < ?)
			""".trimIndent(),
			workerId,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
			candidate.first,
			candidate.second,
			candidate.third,
			Timestamp.from(staleBefore),
		)
		if (updated == 1) ClaimedAgentRun(candidate.first, candidate.second, candidate.third + 1, workerId) else null
	}

	fun recordAgentInfrastructureFailure(
		claim: ClaimedAgentRun,
		now: Instant = currentInstant(),
	) {
		transactionTemplate.executeWithoutResult {
			requireAgentClaim(claim)
			val updated = jdbcTemplate.update(
				"""
				update agent_runs
				set attempt_count = attempt_count + 1,
				    failure_code = 'AGENT_INFRASTRUCTURE_FAILURE', updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
				  and status = 'RUNNING' and attempt_count < max_attempts
				""".trimIndent(),
				Timestamp.from(now),
				claim.workspaceId,
				claim.agentRunId,
				claim.workerId,
				claim.transitionVersion,
			)
			if (updated != 1) throw AgentRunClaimLostException()
		}
	}

	fun beginModelDecision(claim: ClaimedAgentRun, maxModelCalls: Int): AgentRunRecord = transactionTemplate.execute {
		require(maxModelCalls > 0) { "Agent model-call budget must be positive" }
		val run = requireAgentClaim(claim)
		requireAllAgentSourcesActiveForUpdate(claim.workspaceId, claim.agentRunId)
		if (run.modelCallCount >= maxModelCalls) {
			throw AgentRunBudgetExceededException("AGENT_MODEL_CALL_LIMIT")
		}
		val updated = jdbcTemplate.update(
			"""
			update agent_runs
			set model_call_count = model_call_count + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
			  and status = 'RUNNING'
			""".trimIndent(),
			Timestamp.from(currentInstant()),
			claim.workspaceId,
			claim.agentRunId,
			claim.workerId,
			claim.transitionVersion,
		)
		if (updated != 1) throw AgentRunClaimLostException()
		requireNotNull(findAgentRun(claim.workspaceId, claim.agentRunId))
	}

	fun reserveStep(
		claim: ClaimedAgentRun,
		request: AgentStepRequest,
		maxToolCalls: Int,
		now: Instant = currentInstant(),
	): AgentStepRecord = transactionTemplate.execute {
		val run = requireAgentClaim(claim)
		requireAllAgentSourcesActiveForUpdate(claim.workspaceId, claim.agentRunId)
		require(request.agentRunId == claim.agentRunId) { "Agent step belongs to another run" }
		require(request.sequence == run.currentStep) { "Agent step sequence is stale" }
		require(request.status == AgentStepStatus.RUNNING) { "Reserved Agent step must be running" }
		findStepBySequence(claim.workspaceId, claim.agentRunId, request.sequence)?.let { existing ->
			if (existing.idempotencyKey != request.idempotencyKey || existing.kind != request.kind) {
				throw RoutineExecutionStateException("Agent step idempotency conflict")
			}
			return@execute existing
		}
		if (request.kind == AgentStepKind.READ_TOOL && run.toolCallCount >= maxToolCalls) {
			throw AgentRunBudgetExceededException("AGENT_TOOL_CALL_LIMIT")
		}
		val id = uuidGenerator.next()
		jdbcTemplate.update(
			"""
			insert into agent_steps (
			  id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
			  tool_name, arguments, started_at, created_at
			) values (?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?::jsonb, ?, ?)
			""".trimIndent(),
			id,
			claim.workspaceId,
			claim.agentRunId,
			request.sequence,
			request.kind.name,
			request.idempotencyKey.trim(),
			request.toolName,
			request.argumentsJson,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		if (request.kind == AgentStepKind.READ_TOOL) {
			val incremented = jdbcTemplate.update(
				"""
				update agent_runs
				set tool_call_count = tool_call_count + 1, updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
				  and status = 'RUNNING' and tool_call_count < ?
				""".trimIndent(),
				Timestamp.from(now),
				claim.workspaceId,
				claim.agentRunId,
				claim.workerId,
				claim.transitionVersion,
				maxToolCalls,
			)
			if (incremented != 1) throw AgentRunClaimLostException()
		}
		requireNotNull(findStep(claim.workspaceId, claim.agentRunId, id))
	}

	fun completeToolStep(
		claim: ClaimedAgentRun,
		stepId: UUID,
		resultJson: String,
		adoptedInput: AgentRunInputRequest? = null,
		sourceScopeId: UUID? = null,
		sourceStatusChangedAt: Instant? = null,
		maxEvidenceCharacters: Int,
		now: Instant = currentInstant(),
	): AgentStepRecord = transactionTemplate.execute {
		val run = requireAgentClaim(claim)
		val step = findStepForUpdate(claim.workspaceId, claim.agentRunId, stepId)
			?: throw RoutineExecutionStateException("Agent step was not found")
		require(step.sequence == run.currentStep && step.kind == AgentStepKind.READ_TOOL) {
			"Agent read step is stale"
		}
		require(step.status == AgentStepStatus.RUNNING) { "Agent read step is not running" }
		requireAllAgentSourcesActiveForUpdate(claim.workspaceId, claim.agentRunId)
		if (sourceScopeId != null && sourceStatusChangedAt != null) {
			requireSourceVersion(claim.workspaceId, claim.agentRunId, sourceScopeId, sourceStatusChangedAt)
		}

		val adopted = adoptedInput?.let { input ->
			require(input.inputKind == AgentRunInputKind.TOOL_RESULT && input.routineId == null) {
				"Agent read may adopt only tool-result input"
			}
			require(input.sourceScopeId == sourceScopeId) { "Agent read result source mismatch" }
			val currentCharacters = jdbcTemplate.queryForObject(
				"select coalesce(sum(length(coalesce(snapshot_title, '')) + length(snapshot_body)), 0) from agent_run_inputs where workspace_id = ? and agent_run_id = ?",
				Long::class.java,
				claim.workspaceId,
				claim.agentRunId,
			) ?: 0L
			if (currentCharacters + input.snapshotTitle.orEmpty().length + input.snapshotBody.length > maxEvidenceCharacters) {
				throw AgentRunBudgetExceededException("AGENT_EVIDENCE_LIMIT")
			}
			findAdoptedInput(claim.workspaceId, claim.agentRunId, input)
				?: insertAdoptedInput(claim.workspaceId, claim.agentRunId, input, now)
		}

		val stepUpdated = jdbcTemplate.update(
			"""
			update agent_steps
			set status = 'SUCCEEDED', result = ?::jsonb, adopted_input_id = ?, finished_at = ?
			where workspace_id = ? and id = ? and agent_run_id = ? and status = 'RUNNING'
			""".trimIndent(),
			resultJson,
			adopted?.id,
			Timestamp.from(now),
			claim.workspaceId,
			stepId,
			claim.agentRunId,
		)
		if (stepUpdated != 1) throw AgentRunClaimLostException()
		advanceAndRelease(claim, run.currentStep + 1, now)
		requireNotNull(findStep(claim.workspaceId, claim.agentRunId, stepId))
	}

	fun linkGenerationStep(
		claim: ClaimedAgentRun,
		stepId: UUID,
		generationRunId: UUID,
		resultJson: String,
		nextAttemptAt: Instant,
		now: Instant = currentInstant(),
	): AgentStepRecord = transactionTemplate.execute {
		val run = requireAgentClaim(claim)
		val step = findStepForUpdate(claim.workspaceId, claim.agentRunId, stepId)
			?: throw RoutineExecutionStateException("Agent step was not found")
		require(step.sequence == run.currentStep && step.kind == AgentStepKind.ARTIFACT_HANDOFF) {
			"Agent handoff step is stale"
		}
		val generationBelongsToAgent = jdbcTemplate.queryForObject(
			"select count(*) from generation_runs where workspace_id = ? and id = ? and agent_run_id = ? and work_session_id = ?",
			Int::class.java,
			claim.workspaceId,
			generationRunId,
			claim.agentRunId,
			requireNotNull(run.workSessionId),
		) == 1
		if (!generationBelongsToAgent) throw RoutineExecutionStateException("Generation handoff belongs to another Agent run")
		val stepUpdated = jdbcTemplate.update(
			"""
			update agent_steps
			set status = 'SUCCEEDED', generation_run_id = ?, result = ?::jsonb, finished_at = ?
			where workspace_id = ? and id = ? and agent_run_id = ? and status = 'RUNNING'
			""".trimIndent(),
			generationRunId,
			resultJson,
			Timestamp.from(now),
			claim.workspaceId,
			stepId,
			claim.agentRunId,
		)
		if (stepUpdated != 1) throw AgentRunClaimLostException()
		advanceAndRelease(claim, run.currentStep + 1, now, nextAttemptAt)
		requireNotNull(findStep(claim.workspaceId, claim.agentRunId, stepId))
	}

	fun releaseAgentClaim(claim: ClaimedAgentRun, nextAttemptAt: Instant, now: Instant = currentInstant()) {
		transactionTemplate.executeWithoutResult {
			requireAgentClaim(claim)
			advanceAndRelease(claim, currentStep = null, now = now, nextAttemptAt = nextAttemptAt)
		}
	}

	fun scheduleAgentRetry(
		claim: ClaimedAgentRun,
		errorCode: String,
		nextAttemptAt: Instant,
		now: Instant = currentInstant(),
	): AgentRunRecord = transactionTemplate.execute {
		require(errorCode.matches(SAFE_ERROR_CODE)) { "Agent error code is invalid" }
		val run = requireAgentClaim(claim)
		val attempts = run.attemptCount + 1
		val terminal = attempts >= run.maxAttempts
		val updated = jdbcTemplate.update(
			"""
			update agent_runs
			set status = ?, attempt_count = ?, failure_code = ?, next_attempt_at = ?,
			    claimed_by = null, claimed_at = null, transition_version = transition_version + 1,
			    finished_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ? and status = 'RUNNING'
			""".trimIndent(),
			if (terminal) AgentRunStatus.FAILED.name else AgentRunStatus.QUEUED.name,
			attempts,
			errorCode,
			if (terminal) null else Timestamp.from(nextAttemptAt),
			if (terminal) Timestamp.from(now) else null,
			Timestamp.from(now),
			claim.workspaceId,
			claim.agentRunId,
			claim.workerId,
			claim.transitionVersion,
		)
		if (updated != 1) throw AgentRunClaimLostException()
		requireNotNull(findAgentRun(claim.workspaceId, claim.agentRunId))
	}

	fun failAgentRun(claim: ClaimedAgentRun, errorCode: String, now: Instant = currentInstant()): AgentRunRecord =
		terminalizeAgentRun(claim, AgentRunStatus.FAILED, errorCode, now)

	fun succeedAgentRun(claim: ClaimedAgentRun, now: Instant = currentInstant()): AgentRunRecord = transactionTemplate.execute {
		requireAgentClaim(claim)
		val materialized = jdbcTemplate.queryForObject(
			"""
			select count(*)
			from generation_runs generation
			join content_packs pack
			  on pack.workspace_id = generation.workspace_id and pack.generation_run_id = generation.id
			where generation.workspace_id = ? and generation.agent_run_id = ?
			  and generation.status in ('READY', 'NEEDS_REVIEW')
			""".trimIndent(),
			Int::class.java,
			claim.workspaceId,
			claim.agentRunId,
		) == 1
		if (!materialized) throw RoutineExecutionStateException("Agent generation is not materialized")
		terminalizeAgentRun(claim, AgentRunStatus.SUCCEEDED, null, now)
	}

	fun loadGenerationState(workspaceId: UUID, generationRunId: UUID): AgentGenerationState? = jdbcTemplate.query(
		"""
		select generation.id, generation.status,
		       exists (
		         select 1 from content_packs pack
		         where pack.workspace_id = generation.workspace_id and pack.generation_run_id = generation.id
		       ) as materialized
		from generation_runs generation
		where generation.workspace_id = ? and generation.id = ?
		""".trimIndent(),
		{ rs, _ -> AgentGenerationState(
			rs.getObject("id", UUID::class.java),
			rs.getString("status"),
			rs.getBoolean("materialized"),
		) },
		workspaceId,
		generationRunId,
	).singleOrNull()

	fun allAgentSourcesActive(workspaceId: UUID, agentRunId: UUID): Boolean {
		val counts = jdbcTemplate.query(
			"""
			select count(*) as total,
			       count(*) filter (
			         where scope.status = 'ACTIVE' and namespace.status = 'ACTIVE'
			           and exists (
			             select 1
			             from connection_namespace_bindings binding
			             join connections connection
			               on connection.workspace_id = binding.workspace_id
			              and connection.id = binding.connection_id
			              and connection.provider = binding.provider
			              and connection.status = 'ACTIVE'
			             where binding.workspace_id = namespace.workspace_id
			               and binding.source_namespace_id = namespace.id
			               and binding.provider = namespace.provider
			               and binding.status = 'ACTIVE'
			           )
			       ) as active
			from agent_run_sources source
			join source_scopes scope
			  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
			join source_namespaces namespace
			  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
			 and namespace.provider = scope.provider
			where source.workspace_id = ? and source.agent_run_id = ?
			""".trimIndent(),
			{ rs, _ -> rs.getInt("total") to rs.getInt("active") },
			workspaceId,
			agentRunId,
		).single()
		return counts.first > 0 && counts.first == counts.second
	}

	fun findRunningStep(workspaceId: UUID, agentRunId: UUID, sequence: Int): AgentStepRecord? =
		findStepBySequence(workspaceId, agentRunId, sequence)?.takeIf { it.status == AgentStepStatus.RUNNING }

	private fun requireAgentClaim(claim: ClaimedAgentRun): AgentRunRecord {
		val run = jdbcTemplate.query(
			selectAgentRunSql + """

			where a.workspace_id = ? and a.id = ? and a.claimed_by = ? and a.transition_version = ?
			  and a.status = 'RUNNING'
			for update
			""".trimIndent(),
			agentRunMapper,
			claim.workspaceId,
			claim.agentRunId,
			claim.workerId,
			claim.transitionVersion,
		).singleOrNull()
		return run ?: throw AgentRunClaimLostException()
	}

	private fun findStepBySequence(workspaceId: UUID, agentRunId: UUID, sequence: Int): AgentStepRecord? =
		jdbcTemplate.query(
			"""
			select id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
			       tool_name, arguments::text, result::text, adopted_input_id, generation_run_id,
			       failure_code, started_at, finished_at, created_at
			from agent_steps
			where workspace_id = ? and agent_run_id = ? and sequence = ?
			""".trimIndent(),
			agentStepMapper,
			workspaceId,
			agentRunId,
			sequence,
		).singleOrNull()

	private fun findStepForUpdate(workspaceId: UUID, agentRunId: UUID, stepId: UUID): AgentStepRecord? =
		jdbcTemplate.query(
			"""
			select id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
			       tool_name, arguments::text, result::text, adopted_input_id, generation_run_id,
			       failure_code, started_at, finished_at, created_at
			from agent_steps
			where workspace_id = ? and agent_run_id = ? and id = ?
			for update
			""".trimIndent(),
			agentStepMapper,
			workspaceId,
			agentRunId,
			stepId,
		).singleOrNull()

	private fun requireAllAgentSourcesActiveForUpdate(workspaceId: UUID, agentRunId: UUID) {
		val expected = jdbcTemplate.queryForObject(
			"select count(*) from agent_run_sources where workspace_id = ? and agent_run_id = ?",
			Int::class.java,
			workspaceId,
			agentRunId,
		) ?: 0
		val statuses = jdbcTemplate.query(
			"""
			select scope.id, scope.status, namespace.status as namespace_status
			from agent_run_sources source
			join source_scopes scope
			  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
			join source_namespaces namespace
			  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
			 and namespace.provider = scope.provider
			where source.workspace_id = ? and source.agent_run_id = ?
			order by scope.id
			for update of scope, namespace
			""".trimIndent(),
			{ rs, _ -> Triple(
				rs.getObject("id", UUID::class.java),
				rs.getString("status"),
				rs.getString("namespace_status"),
			) },
			workspaceId,
			agentRunId,
		)
		val connectedScopeIds = jdbcTemplate.query(
			"""
			select scope.id
			from agent_run_sources source
			join source_scopes scope
			  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
			join connection_namespace_bindings binding
			  on binding.workspace_id = scope.workspace_id
			 and binding.source_namespace_id = scope.source_namespace_id
			 and binding.provider = scope.provider
			join connections connection
			  on connection.workspace_id = binding.workspace_id
			 and connection.id = binding.connection_id
			 and connection.provider = binding.provider
			where source.workspace_id = ? and source.agent_run_id = ?
			  and binding.status = 'ACTIVE' and connection.status = 'ACTIVE'
			order by scope.id
			for update of binding, connection
			""".trimIndent(),
			{ rs, _ -> rs.getObject("id", UUID::class.java) },
			workspaceId,
			agentRunId,
		).toSet()
		if (
			expected == 0 ||
			statuses.size != expected ||
			statuses.any { it.second != "ACTIVE" || it.third != "ACTIVE" } ||
			connectedScopeIds != statuses.map { it.first }.toSet()
		) {
			throw AgentToolAccessException("SOURCE_NOT_READY")
		}
	}

	private fun requireSourceVersion(
		workspaceId: UUID,
		agentRunId: UUID,
		sourceScopeId: UUID,
		expectedStatusChangedAt: Instant,
	) {
		val current = jdbcTemplate.query(
			"""
			select greatest(
			  scope.status_changed_at,
			  namespace.updated_at,
			  binding.updated_at,
			  connection.updated_at
			) as lifecycle_version_at
			from agent_run_sources source
			join source_scopes scope
			  on scope.workspace_id = source.workspace_id and scope.id = source.source_scope_id
			join source_namespaces namespace
			  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
			 and namespace.provider = scope.provider and namespace.status = 'ACTIVE'
			join connection_namespace_bindings binding
			  on binding.workspace_id = namespace.workspace_id and binding.source_namespace_id = namespace.id
			 and binding.provider = namespace.provider and binding.status = 'ACTIVE'
			join connections connection
			  on connection.workspace_id = binding.workspace_id and connection.id = binding.connection_id
			 and connection.provider = binding.provider and connection.status = 'ACTIVE'
			where source.workspace_id = ? and source.agent_run_id = ? and source.source_scope_id = ?
			  and scope.status = 'ACTIVE'
			""".trimIndent(),
			{ rs, _ -> rs.getTimestamp("lifecycle_version_at").toInstant() },
			workspaceId,
			agentRunId,
			sourceScopeId,
		).singleOrNull()
		if (current != expectedStatusChangedAt) throw AgentToolAccessException("SOURCE_CHANGED_DURING_READ")
	}

	private fun findAdoptedInput(
		workspaceId: UUID,
		agentRunId: UUID,
		input: AgentRunInputRequest,
	): AgentRunInputRecord? = jdbcTemplate.query(
		"""
		select id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
		       source_provider, source_kind, source_label,
		       input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
		       snapshot_excerpt, original_url, source_created_at, source_updated_at,
		       content_hash, captured_at
		from agent_run_inputs
		where workspace_id = ? and agent_run_id = ? and input_kind = 'TOOL_RESULT'
		  and source_scope_id = ? and writing_block_id = ? and content_hash = ?
		""".trimIndent(),
		agentRunInputMapper,
		workspaceId,
		agentRunId,
		input.sourceScopeId,
		input.writingBlockId,
		input.contentHash,
	).singleOrNull()

	private fun insertAdoptedInput(
		workspaceId: UUID,
		agentRunId: UUID,
		input: AgentRunInputRequest,
		now: Instant,
	): AgentRunInputRecord {
		val orderIndex = jdbcTemplate.queryForObject(
			"select coalesce(max(order_index), -1) + 1 from agent_run_inputs where workspace_id = ? and agent_run_id = ?",
			Int::class.java,
			workspaceId,
			agentRunId,
		) ?: 0
		val id = uuidGenerator.next()
		jdbcTemplate.update(
			"""
			insert into agent_run_inputs (
			  id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
			  source_provider, source_kind, source_label,
			  input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
			  snapshot_excerpt, original_url, source_created_at, source_updated_at,
			  content_hash, captured_at
			) values (?, ?, ?, null, ?, ?, ?, ?, ?, 'TOOL_RESULT', ?, null, ?, ?, ?, ?, ?, ?, ?, ?)
			on conflict (workspace_id, agent_run_id, source_scope_id, writing_block_id, content_hash)
			where input_kind = 'TOOL_RESULT'
			do nothing
			""".trimIndent(),
			id,
			workspaceId,
			agentRunId,
			input.sourceScopeId,
			input.writingBlockId,
			input.sourceProvider,
			input.sourceKind,
			input.sourceLabel,
			orderIndex,
			input.snapshotTitle,
			input.snapshotBody,
			input.snapshotExcerpt,
			input.originalUrl,
			input.sourceCreatedAt?.let(Timestamp::from),
			input.sourceUpdatedAt?.let(Timestamp::from),
			input.contentHash,
			Timestamp.from(input.capturedAt.takeIf { !it.isAfter(now) } ?: now),
		)
		return findAdoptedInput(workspaceId, agentRunId, input)
			?: throw RoutineExecutionStateException("Agent read result could not be adopted")
	}

	private fun advanceAndRelease(
		claim: ClaimedAgentRun,
		currentStep: Int?,
		now: Instant,
		nextAttemptAt: Instant? = null,
	) {
		val updated = if (currentStep == null) {
			jdbcTemplate.update(
				"""
				update agent_runs
				set claimed_by = null, claimed_at = null, next_attempt_at = ?,
				    transition_version = transition_version + 1, updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ? and status = 'RUNNING'
				""".trimIndent(),
				nextAttemptAt?.let(Timestamp::from),
				Timestamp.from(now),
				claim.workspaceId,
				claim.agentRunId,
				claim.workerId,
				claim.transitionVersion,
			)
		} else {
			jdbcTemplate.update(
				"""
				update agent_runs
				set current_step = ?, claimed_by = null, claimed_at = null, next_attempt_at = ?,
				    transition_version = transition_version + 1, updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ? and status = 'RUNNING'
				""".trimIndent(),
				currentStep,
				nextAttemptAt?.let(Timestamp::from),
				Timestamp.from(now),
				claim.workspaceId,
				claim.agentRunId,
				claim.workerId,
				claim.transitionVersion,
			)
		}
		if (updated != 1) throw AgentRunClaimLostException()
	}

	private fun terminalizeAgentRun(
		claim: ClaimedAgentRun,
		status: AgentRunStatus,
		errorCode: String?,
		now: Instant,
	): AgentRunRecord = transactionTemplate.execute {
		require(status in setOf(AgentRunStatus.SUCCEEDED, AgentRunStatus.FAILED)) { "Agent terminal status is invalid" }
		if (errorCode != null) require(errorCode.matches(SAFE_ERROR_CODE)) { "Agent error code is invalid" }
		requireAgentClaim(claim)
		if (status == AgentRunStatus.FAILED) {
			jdbcTemplate.update(
				"""
				update agent_steps
				set status = 'FAILED', failure_code = ?, finished_at = coalesce(finished_at, ?)
				where workspace_id = ? and agent_run_id = ? and status = 'RUNNING'
				""".trimIndent(),
				requireNotNull(errorCode),
				Timestamp.from(now),
				claim.workspaceId,
				claim.agentRunId,
			)
		}
		val updated = jdbcTemplate.update(
			"""
			update agent_runs
			set status = ?, failure_code = ?, claimed_by = null, claimed_at = null, next_attempt_at = null,
			    finished_at = ?, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ? and status = 'RUNNING'
			""".trimIndent(),
			status.name,
			errorCode,
			Timestamp.from(now),
			Timestamp.from(now),
			claim.workspaceId,
			claim.agentRunId,
			claim.workerId,
			claim.transitionVersion,
		)
		if (updated != 1) throw AgentRunClaimLostException()
		requireNotNull(findAgentRun(claim.workspaceId, claim.agentRunId))
	}

	private fun findStep(workspaceId: UUID, agentRunId: UUID, id: UUID): AgentStepRecord? = jdbcTemplate.query(
		"""
		select id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
		       tool_name, arguments::text, result::text, adopted_input_id, generation_run_id,
		       failure_code, started_at, finished_at, created_at
		from agent_steps
		where workspace_id = ? and agent_run_id = ? and id = ?
		""".trimIndent(),
		agentStepMapper,
		workspaceId,
		agentRunId,
		id,
	).firstOrNull()

	private fun failExhaustedStaleAgentRuns(staleBefore: Instant, now: Instant) {
		val exhausted = jdbcTemplate.query(
			"""
			select workspace_id, id
			from agent_runs
			where status = 'RUNNING' and claimed_by is not null and claimed_at < ?
			  and attempt_count >= max_attempts
			order by created_at, id
			for update skip locked
			""".trimIndent(),
			{ rs, _ ->
				rs.getObject("workspace_id", UUID::class.java) to
					rs.getObject("id", UUID::class.java)
			},
			Timestamp.from(staleBefore),
		)
		exhausted.forEach { (workspaceId, agentRunId) ->
			jdbcTemplate.update(
				"""
				update agent_steps
				set status = 'FAILED', failure_code = 'AGENT_RETRY_EXHAUSTED',
				    finished_at = coalesce(finished_at, ?)
				where workspace_id = ? and agent_run_id = ? and status = 'RUNNING'
				""".trimIndent(),
				Timestamp.from(now),
				workspaceId,
				agentRunId,
			)
			jdbcTemplate.update(
				"""
				update agent_runs
				set status = 'FAILED', failure_code = 'AGENT_RETRY_EXHAUSTED',
				    claimed_by = null, claimed_at = null, next_attempt_at = null,
				    finished_at = ?, transition_version = transition_version + 1, updated_at = ?
				where workspace_id = ? and id = ? and status = 'RUNNING'
				""".trimIndent(),
				Timestamp.from(now),
				Timestamp.from(now),
				workspaceId,
				agentRunId,
			)
		}
	}

	private fun findInput(workspaceId: UUID, agentRunId: UUID, id: UUID): AgentRunInputRecord? = jdbcTemplate.query(
		"""
		select id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
		       source_provider, source_kind, source_label,
		       input_kind, order_index, activity_sequence, snapshot_title, snapshot_body,
		       snapshot_excerpt, original_url, source_created_at, source_updated_at,
		       content_hash, captured_at
		from agent_run_inputs
		where workspace_id = ? and agent_run_id = ? and id = ?
		""".trimIndent(),
		agentRunInputMapper,
		workspaceId,
		agentRunId,
		id,
	).firstOrNull()


	private fun findExecutionForUpdate(workspaceId: UUID, id: UUID): RoutineExecutionRecord? = jdbcTemplate.query(
		selectExecutionSql + " where e.workspace_id = ? and e.id = ? for update",
		executionMapper,
		workspaceId,
		id,
	).firstOrNull()

	private fun findRoutineCursorForUpdate(workspaceId: UUID, routineId: UUID): RoutineCursor? = jdbcTemplate.query(
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
		val configuredContextSources = jdbcTemplate.query(
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
		val rows = jdbcTemplate.query(
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
					id = rs.getObject("id", UUID::class.java),
					status = rs.getString("status"),
					statusChangedAt = rs.getTimestamp("lifecycle_version_at").toInstant(),
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


	private val executionMapper = { rs: ResultSet, _: Int -> rs.toRoutineExecution() }
	private val agentRunMapper = { rs: ResultSet, _: Int -> rs.toAgentRun() }
	private val agentRunSourceMapper = { rs: ResultSet, _: Int -> rs.toAgentRunSource() }
	private val agentRunInputMapper = { rs: ResultSet, _: Int -> rs.toAgentRunInput() }
	private val agentStepMapper = { rs: ResultSet, _: Int -> rs.toAgentStep() }
	private data class RoutineCursor(val value: Long?)
	private data class LockedSource(val id: UUID, val status: String, val statusChangedAt: Instant)

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

	private val selectAgentRunSql = """
		select a.id, a.workspace_id, a.routine_execution_id, a.work_session_id, a.routine_id, a.created_by_user_id,
		       a.instruction_snapshot, a.prompt_version, a.tool_policy_version, a.budget_snapshot::text,
		       a.status, a.current_step, a.attempt_count, a.max_attempts,
		       a.model_call_count, a.tool_call_count, a.next_attempt_at,
		       a.failure_code, a.claimed_by, a.claimed_at, a.transition_version, a.started_at,
		       a.finished_at, a.created_at, a.updated_at
		from agent_runs a
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

	private fun ResultSet.toAgentRun() = AgentRunRecord(
		id = getObject("id", UUID::class.java),
		workspaceId = getObject("workspace_id", UUID::class.java),
		routineExecutionId = getObject("routine_execution_id", UUID::class.java),
		workSessionId = getObject("work_session_id", UUID::class.java),
		routineId = getObject("routine_id", UUID::class.java),
		createdByUserId = getObject("created_by_user_id", UUID::class.java),
		instructionSnapshot = getString("instruction_snapshot"),
		promptVersion = getString("prompt_version"),
		toolPolicyVersion = getString("tool_policy_version"),
		budgetSnapshotJson = getString("budget_snapshot"),
		status = AgentRunStatus.valueOf(getString("status")),
		currentStep = getInt("current_step"),
		attemptCount = getInt("attempt_count"),
		maxAttempts = getInt("max_attempts"),
		modelCallCount = getInt("model_call_count"),
		toolCallCount = getInt("tool_call_count"),
		nextAttemptAt = getTimestamp("next_attempt_at")?.toInstant(),
		failureCode = getString("failure_code"),
		claimedBy = getString("claimed_by"),
		claimedAt = getTimestamp("claimed_at")?.toInstant(),
		transitionVersion = getLong("transition_version"),
		startedAt = getTimestamp("started_at")?.toInstant(),
		finishedAt = getTimestamp("finished_at")?.toInstant(),
		createdAt = getTimestamp("created_at").toInstant(),
		updatedAt = getTimestamp("updated_at").toInstant(),
	)

	private fun ResultSet.toAgentRunSource() = AgentRunSourceRecord(
		id = getObject("id", UUID::class.java),
		workspaceId = getObject("workspace_id", UUID::class.java),
		agentRunId = getObject("agent_run_id", UUID::class.java),
		sourceScopeId = getObject("source_scope_id", UUID::class.java),
		role = AgentRunSourceRole.valueOf(getString("source_role")),
		orderIndex = getInt("order_index"),
		capturedStatus = getString("captured_status"),
		capturedStatusChangedAt = getTimestamp("captured_status_changed_at").toInstant(),
		capturedAt = getTimestamp("captured_at").toInstant(),
	)

	private fun ResultSet.toAgentRunInput() = AgentRunInputRecord(
		id = getObject("id", UUID::class.java),
		workspaceId = getObject("workspace_id", UUID::class.java),
		agentRunId = getObject("agent_run_id", UUID::class.java),
		routineId = getObject("routine_id", UUID::class.java),
		sourceScopeId = getObject("source_scope_id", UUID::class.java),
		writingBlockId = getObject("writing_block_id", UUID::class.java),
		sourceProvider = getString("source_provider"),
		sourceKind = getString("source_kind"),
		sourceLabel = getString("source_label"),
		inputKind = AgentRunInputKind.valueOf(getString("input_kind")),
		orderIndex = getInt("order_index"),
		activitySequence = getObject("activity_sequence", Long::class.javaObjectType),
		snapshotTitle = getString("snapshot_title"),
		snapshotBody = getString("snapshot_body"),
		snapshotExcerpt = getString("snapshot_excerpt"),
		originalUrl = getString("original_url"),
		sourceCreatedAt = getTimestamp("source_created_at")?.toInstant(),
		sourceUpdatedAt = getTimestamp("source_updated_at")?.toInstant(),
		contentHash = getString("content_hash"),
		capturedAt = getTimestamp("captured_at").toInstant(),
	)

	private fun ResultSet.toAgentStep() = AgentStepRecord(
		id = getObject("id", UUID::class.java),
		workspaceId = getObject("workspace_id", UUID::class.java),
		agentRunId = getObject("agent_run_id", UUID::class.java),
		sequence = getInt("sequence"),
		kind = AgentStepKind.valueOf(getString("step_kind")),
		status = AgentStepStatus.valueOf(getString("status")),
		idempotencyKey = getString("idempotency_key"),
		toolName = getString("tool_name"),
		argumentsJson = getString("arguments"),
		resultJson = getString("result"),
		adoptedInputId = getObject("adopted_input_id", UUID::class.java),
		generationRunId = getObject("generation_run_id", UUID::class.java),
		failureCode = getString("failure_code"),
		startedAt = getTimestamp("started_at")?.toInstant(),
		finishedAt = getTimestamp("finished_at")?.toInstant(),
		createdAt = getTimestamp("created_at").toInstant(),
	)

}
