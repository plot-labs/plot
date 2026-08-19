package com.plot.api.routine

import com.plot.api.persistence.generated.tables.AgentRuns.Companion.AGENT_RUNS
import com.plot.api.persistence.generated.tables.AgentRunInputs.Companion.AGENT_RUN_INPUTS
import com.plot.api.persistence.generated.tables.AgentSteps.Companion.AGENT_STEPS
import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Component

@Component
class AgentRunExecutionPersistence(
	private val sqlExecutor: JooqSqlExecutor,
	private val transactionExecutor: JooqTransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val queryPersistence: AgentRunQueryPersistence,
	dslContext: DSLContext,
	private val clock: Clock? = null,
) {
	private val dsl: DSLContext = dslContext.configuration()
		.derive(dslContext.settings().withRenderSchema(false))
		.dsl()
	private val safeErrorCode = Regex("[A-Z][A-Z0-9_]{0,99}")
	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	fun appendStep(
		workspaceId: UUID,
		request: AgentStepRequest,
		now: Instant = currentInstant(),
	): AgentStepRecord = transactionExecutor.execute {
		val id = uuidGenerator.next()
		sqlExecutor.executeTyped(dsl) { context ->
			context.insertInto(AGENT_STEPS)
				.set(AGENT_STEPS.ID, id)
				.set(AGENT_STEPS.WORKSPACE_ID, workspaceId)
				.set(AGENT_STEPS.AGENT_RUN_ID, request.agentRunId)
				.set(AGENT_STEPS.SEQUENCE, request.sequence)
				.set(AGENT_STEPS.STEP_KIND, request.kind.name)
				.set(AGENT_STEPS.STATUS, request.status.name)
				.set(AGENT_STEPS.IDEMPOTENCY_KEY, request.idempotencyKey.trim())
				.set(AGENT_STEPS.TOOL_NAME, request.toolName)
				.set(AGENT_STEPS.ARGUMENTS, JSONB.valueOf(request.argumentsJson))
				.set(AGENT_STEPS.RESULT, request.resultJson?.let(JSONB::valueOf))
				.set(AGENT_STEPS.ADOPTED_INPUT_ID, request.adoptedInputId)
				.set(AGENT_STEPS.GENERATION_RUN_ID, request.artifactWorkflowRunId)
				.set(AGENT_STEPS.FAILURE_CODE, request.failureCode)
				.set(AGENT_STEPS.STARTED_AT, request.startedAt?.toOffsetDateTime())
				.set(AGENT_STEPS.FINISHED_AT, request.finishedAt?.toOffsetDateTime())
				.set(AGENT_STEPS.CREATED_AT, now.toOffsetDateTime())
				.execute()
		}
		queryPersistence.findStep(workspaceId, request.agentRunId, id)
	} ?: error("Agent step transaction returned no record")
	fun claimNextAgentRun(
		workerId: String,
		now: Instant = currentInstant(),
		staleBefore: Instant,
	): ClaimedAgentRun? = transactionExecutor.execute {
		failExhaustedStaleAgentRuns(staleBefore, now)
		val candidate = sqlExecutor.query(
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
				requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
				requireNotNull(rs.getObject("id", UUID::class.java)),
				rs.getLong("transition_version"),
			) },
			Timestamp.from(now),
			Timestamp.from(staleBefore),
		).firstOrNull() ?: return@execute null
		val updated = dsl.update(AGENT_RUNS)
			.set(AGENT_RUNS.STATUS, AgentRunStatus.RUNNING.name)
			.set(AGENT_RUNS.CLAIMED_BY, workerId)
			.set(AGENT_RUNS.CLAIMED_AT, now.toOffsetDateTime())
			.set(AGENT_RUNS.NEXT_ATTEMPT_AT, null as OffsetDateTime?)
			.set(AGENT_RUNS.STARTED_AT, AGENT_RUNS.STARTED_AT.coalesce(now.toOffsetDateTime()))
			.set(AGENT_RUNS.TRANSITION_VERSION, AGENT_RUNS.TRANSITION_VERSION.plus(1))
			.set(AGENT_RUNS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				AGENT_RUNS.WORKSPACE_ID.eq(candidate.first),
				AGENT_RUNS.ID.eq(candidate.second),
				AGENT_RUNS.TRANSITION_VERSION.eq(candidate.third),
				AGENT_RUNS.STATUS.`in`(AgentRunStatus.QUEUED.name, AgentRunStatus.RUNNING.name),
				AGENT_RUNS.ATTEMPT_COUNT.lt(AGENT_RUNS.MAX_ATTEMPTS),
				AGENT_RUNS.CLAIMED_BY.isNull.or(AGENT_RUNS.CLAIMED_AT.lt(staleBefore.toOffsetDateTime())),
			)
			.execute()
		if (updated == 1) ClaimedAgentRun(candidate.first, candidate.second, candidate.third + 1, workerId) else null
	}
	fun recordAgentInfrastructureFailure(
		claim: ClaimedAgentRun,
		now: Instant = currentInstant(),
	) {
		transactionExecutor.executeWithoutResult {
			queryPersistence.requireAgentClaim(claim)
			val updated = dsl.update(AGENT_RUNS)
				.set(AGENT_RUNS.ATTEMPT_COUNT, AGENT_RUNS.ATTEMPT_COUNT.plus(1))
				.set(AGENT_RUNS.FAILURE_CODE, "AGENT_INFRASTRUCTURE_FAILURE")
				.set(AGENT_RUNS.UPDATED_AT, now.toOffsetDateTime())
				.where(
					AGENT_RUNS.WORKSPACE_ID.eq(claim.workspaceId),
					AGENT_RUNS.ID.eq(claim.agentRunId),
					AGENT_RUNS.CLAIMED_BY.eq(claim.workerId),
					AGENT_RUNS.TRANSITION_VERSION.eq(claim.transitionVersion),
					AGENT_RUNS.STATUS.eq(AgentRunStatus.RUNNING.name),
					AGENT_RUNS.ATTEMPT_COUNT.lt(AGENT_RUNS.MAX_ATTEMPTS),
				)
				.execute()
			if (updated != 1) throw AgentRunClaimLostException()
		}
	}
	fun beginModelDecision(claim: ClaimedAgentRun, maxModelCalls: Int): AgentRunRecord = transactionExecutor.execute {
		require(maxModelCalls > 0) { "Agent model-call budget must be positive" }
		val run = queryPersistence.requireAgentClaim(claim)
		queryPersistence.requireAllAgentSourcesActiveForUpdate(claim.workspaceId, claim.agentRunId)
		if (run.modelCallCount >= maxModelCalls) {
			throw AgentRunBudgetExceededException("AGENT_MODEL_CALL_LIMIT")
		}
		val updated = dsl.update(AGENT_RUNS)
			.set(AGENT_RUNS.MODEL_CALL_COUNT, AGENT_RUNS.MODEL_CALL_COUNT.plus(1))
			.set(AGENT_RUNS.UPDATED_AT, currentInstant().toOffsetDateTime())
			.where(
				AGENT_RUNS.WORKSPACE_ID.eq(claim.workspaceId),
				AGENT_RUNS.ID.eq(claim.agentRunId),
				AGENT_RUNS.CLAIMED_BY.eq(claim.workerId),
				AGENT_RUNS.TRANSITION_VERSION.eq(claim.transitionVersion),
				AGENT_RUNS.STATUS.eq(AgentRunStatus.RUNNING.name),
			)
			.execute()
		if (updated != 1) throw AgentRunClaimLostException()
		requireNotNull(queryPersistence.findAgentRun(claim.workspaceId, claim.agentRunId))
	}
	fun reserveStep(
		claim: ClaimedAgentRun,
		request: AgentStepRequest,
		maxToolCalls: Int,
		now: Instant = currentInstant(),
	): AgentStepRecord = transactionExecutor.execute {
		val run = queryPersistence.requireAgentClaim(claim)
		queryPersistence.requireAllAgentSourcesActiveForUpdate(claim.workspaceId, claim.agentRunId)
		require(request.agentRunId == claim.agentRunId) { "Agent step belongs to another run" }
		require(request.sequence == run.currentStep) { "Agent step sequence is stale" }
		require(request.status == AgentStepStatus.RUNNING) { "Reserved Agent step must be running" }
		queryPersistence.findStepBySequence(claim.workspaceId, claim.agentRunId, request.sequence)?.let { existing ->
			if (existing.idempotencyKey != request.idempotencyKey || existing.kind != request.kind) {
				throw RoutineExecutionStateException("Agent step idempotency conflict")
			}
			return@execute existing
		}
		if (request.kind == AgentStepKind.READ_TOOL && run.toolCallCount >= maxToolCalls) {
			throw AgentRunBudgetExceededException("AGENT_TOOL_CALL_LIMIT")
		}
		val id = uuidGenerator.next()
		sqlExecutor.executeTyped(dsl) { context ->
			context.insertInto(AGENT_STEPS)
				.set(AGENT_STEPS.ID, id)
				.set(AGENT_STEPS.WORKSPACE_ID, claim.workspaceId)
				.set(AGENT_STEPS.AGENT_RUN_ID, claim.agentRunId)
				.set(AGENT_STEPS.SEQUENCE, request.sequence)
				.set(AGENT_STEPS.STEP_KIND, request.kind.name)
				.set(AGENT_STEPS.STATUS, AgentStepStatus.RUNNING.name)
				.set(AGENT_STEPS.IDEMPOTENCY_KEY, request.idempotencyKey.trim())
				.set(AGENT_STEPS.TOOL_NAME, request.toolName)
				.set(AGENT_STEPS.ARGUMENTS, JSONB.valueOf(request.argumentsJson))
				.set(AGENT_STEPS.STARTED_AT, now.toOffsetDateTime())
				.set(AGENT_STEPS.CREATED_AT, now.toOffsetDateTime())
				.execute()
		}
		if (request.kind == AgentStepKind.READ_TOOL) {
			val incremented = dsl.update(AGENT_RUNS)
				.set(AGENT_RUNS.TOOL_CALL_COUNT, AGENT_RUNS.TOOL_CALL_COUNT.plus(1))
				.set(AGENT_RUNS.UPDATED_AT, now.toOffsetDateTime())
				.where(
					AGENT_RUNS.WORKSPACE_ID.eq(claim.workspaceId),
					AGENT_RUNS.ID.eq(claim.agentRunId),
					AGENT_RUNS.CLAIMED_BY.eq(claim.workerId),
					AGENT_RUNS.TRANSITION_VERSION.eq(claim.transitionVersion),
					AGENT_RUNS.STATUS.eq(AgentRunStatus.RUNNING.name),
					AGENT_RUNS.TOOL_CALL_COUNT.lt(maxToolCalls),
				)
				.execute()
			if (incremented != 1) throw AgentRunClaimLostException()
		}
		requireNotNull(queryPersistence.findStep(claim.workspaceId, claim.agentRunId, id))
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
	): AgentStepRecord = transactionExecutor.execute {
		val run = queryPersistence.requireAgentClaim(claim)
		val step = findStepForUpdate(claim.workspaceId, claim.agentRunId, stepId)
			?: throw RoutineExecutionStateException("Agent step was not found")
		require(step.sequence == run.currentStep && step.kind == AgentStepKind.READ_TOOL) {
			"Agent read step is stale"
		}
		require(step.status == AgentStepStatus.RUNNING) { "Agent read step is not running" }
		queryPersistence.requireAllAgentSourcesActiveForUpdate(claim.workspaceId, claim.agentRunId)
		if (sourceScopeId != null && sourceStatusChangedAt != null) {
			requireSourceVersion(claim.workspaceId, claim.agentRunId, sourceScopeId, sourceStatusChangedAt)
		}

		val adopted = adoptedInput?.let { input ->
			require(input.inputKind == AgentRunInputKind.TOOL_RESULT && input.routineId == null) {
				"Agent read may adopt only tool-result input"
			}
			require(input.sourceScopeId == sourceScopeId) { "Agent read result source mismatch" }
			val currentCharacters = sqlExecutor.queryForObject(
				"select coalesce(sum(length(coalesce(snapshot_title, '')) + length(snapshot_body)), 0) from agent_run_inputs where workspace_id = ? and agent_run_id = ?",
				Long::class.java,
				claim.workspaceId,
				claim.agentRunId,
			) ?: 0L
			if (currentCharacters + input.snapshotTitle.orEmpty().length + input.snapshotBody.length > maxEvidenceCharacters) {
				throw AgentRunBudgetExceededException("AGENT_EVIDENCE_LIMIT")
			}
			queryPersistence.findAdoptedInput(claim.workspaceId, claim.agentRunId, input)
				?: insertAdoptedInput(claim.workspaceId, claim.agentRunId, input, now)
		}

		val stepUpdated = dsl.update(AGENT_STEPS)
			.set(AGENT_STEPS.STATUS, AgentStepStatus.SUCCEEDED.name)
			.set(AGENT_STEPS.RESULT, JSONB.valueOf(resultJson))
			.set(AGENT_STEPS.ADOPTED_INPUT_ID, adopted?.id)
			.set(AGENT_STEPS.FINISHED_AT, now.toOffsetDateTime())
			.where(
				AGENT_STEPS.WORKSPACE_ID.eq(claim.workspaceId),
				AGENT_STEPS.ID.eq(stepId),
				AGENT_STEPS.AGENT_RUN_ID.eq(claim.agentRunId),
				AGENT_STEPS.STATUS.eq(AgentStepStatus.RUNNING.name),
			)
			.execute()
		advanceAndRelease(claim, run.currentStep + 1, now)
		requireNotNull(queryPersistence.findStep(claim.workspaceId, claim.agentRunId, stepId))
	}
	fun linkArtifactWorkflowStep(
		claim: ClaimedAgentRun,
		stepId: UUID,
		artifactWorkflowRunId: UUID,
		resultJson: String,
		nextAttemptAt: Instant,
		now: Instant = currentInstant(),
	): AgentStepRecord = transactionExecutor.execute {
		val run = queryPersistence.requireAgentClaim(claim)
		val step = findStepForUpdate(claim.workspaceId, claim.agentRunId, stepId)
			?: throw RoutineExecutionStateException("Agent step was not found")
		require(step.sequence == run.currentStep && step.kind == AgentStepKind.ARTIFACT_HANDOFF) {
			"Agent handoff step is stale"
		}
		val artifactWorkflowBelongsToAgent = sqlExecutor.queryForObject(
			"select count(*) from generation_runs where workspace_id = ? and id = ? and agent_run_id = ? and work_session_id = ?",
			Int::class.java,
			claim.workspaceId,
			artifactWorkflowRunId,
			claim.agentRunId,
			requireNotNull(run.workSessionId),
		) == 1
		if (!artifactWorkflowBelongsToAgent) throw RoutineExecutionStateException("ArtifactWorkflow handoff belongs to another Agent run")
		val stepUpdated = dsl.update(AGENT_STEPS)
			.set(AGENT_STEPS.STATUS, AgentStepStatus.SUCCEEDED.name)
			.set(AGENT_STEPS.GENERATION_RUN_ID, artifactWorkflowRunId)
			.set(AGENT_STEPS.RESULT, JSONB.valueOf(resultJson))
			.set(AGENT_STEPS.FINISHED_AT, now.toOffsetDateTime())
			.where(
				AGENT_STEPS.WORKSPACE_ID.eq(claim.workspaceId),
				AGENT_STEPS.ID.eq(stepId),
				AGENT_STEPS.AGENT_RUN_ID.eq(claim.agentRunId),
				AGENT_STEPS.STATUS.eq(AgentStepStatus.RUNNING.name),
			)
			.execute()
		if (stepUpdated != 1) throw AgentRunClaimLostException()
		advanceAndRelease(claim, run.currentStep + 1, now, nextAttemptAt)
		requireNotNull(queryPersistence.findStep(claim.workspaceId, claim.agentRunId, stepId))
	}
	fun releaseAgentClaim(claim: ClaimedAgentRun, nextAttemptAt: Instant, now: Instant = currentInstant()) {
		transactionExecutor.executeWithoutResult {
			queryPersistence.requireAgentClaim(claim)
			advanceAndRelease(claim, currentStep = null, now = now, nextAttemptAt = nextAttemptAt)
		}
	}
	fun scheduleAgentRetry(
		claim: ClaimedAgentRun,
		errorCode: String,
		nextAttemptAt: Instant,
		now: Instant = currentInstant(),
	): AgentRunRecord = transactionExecutor.execute {
		require(errorCode.matches(safeErrorCode)) { "Agent error code is invalid" }
		val run = queryPersistence.requireAgentClaim(claim)
		val attempts = run.attemptCount + 1
		val terminal = attempts >= run.maxAttempts
		val updated = dsl.update(AGENT_RUNS)
			.set(AGENT_RUNS.STATUS, if (terminal) AgentRunStatus.FAILED.name else AgentRunStatus.QUEUED.name)
			.set(AGENT_RUNS.ATTEMPT_COUNT, attempts)
			.set(AGENT_RUNS.FAILURE_CODE, errorCode)
			.set(AGENT_RUNS.NEXT_ATTEMPT_AT, if (terminal) null else nextAttemptAt.toOffsetDateTime())
			.set(AGENT_RUNS.CLAIMED_BY, null as String?)
			.set(AGENT_RUNS.CLAIMED_AT, null as OffsetDateTime?)
			.set(AGENT_RUNS.TRANSITION_VERSION, AGENT_RUNS.TRANSITION_VERSION.plus(1))
			.set(AGENT_RUNS.FINISHED_AT, if (terminal) now.toOffsetDateTime() else null)
			.set(AGENT_RUNS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				AGENT_RUNS.WORKSPACE_ID.eq(claim.workspaceId),
				AGENT_RUNS.ID.eq(claim.agentRunId),
				AGENT_RUNS.CLAIMED_BY.eq(claim.workerId),
				AGENT_RUNS.TRANSITION_VERSION.eq(claim.transitionVersion),
				AGENT_RUNS.STATUS.eq(AgentRunStatus.RUNNING.name),
			)
			.execute()
		requireNotNull(queryPersistence.findAgentRun(claim.workspaceId, claim.agentRunId))
	}
	fun failAgentRun(claim: ClaimedAgentRun, errorCode: String, now: Instant = currentInstant()): AgentRunRecord =
		terminalizeAgentRun(claim, AgentRunStatus.FAILED, errorCode, now)
	fun succeedAgentRun(claim: ClaimedAgentRun, now: Instant = currentInstant()): AgentRunRecord = transactionExecutor.execute {
		val run = queryPersistence.requireAgentClaim(claim)
		val materialized = sqlExecutor.queryForObject(
			"""
			select count(*)
			from artifact_runs artifact
			join generation_runs generation
			  on generation.workspace_id = artifact.workspace_id and generation.artifact_run_id = artifact.id
			join content_packs pack
			  on pack.workspace_id = generation.workspace_id and pack.generation_run_id = generation.id
			where artifact.workspace_id = ? and artifact.agent_run_id = ?
			  and artifact.status in ('READY', 'NEEDS_REVIEW')
			""".trimIndent(),
			Int::class.java,
			claim.workspaceId,
			claim.agentRunId,
		) == 1
		if (!materialized) throw RoutineExecutionStateException("Agent artifact workflow is not materialized")
		val completed = terminalizeAgentRun(claim, AgentRunStatus.SUCCEEDED, null, now)
		commitRoutineCursor(run, now)
		completed
	}

	private fun commitRoutineCursor(run: AgentRunRecord, now: Instant) {
		if (run.origin != AgentRunOrigin.ROUTINE || run.routineExecutionId == null || run.routineId == null) return
		sqlExecutor.update(
			"""
			update routines routine
			set activity_cursor_sequence = greatest(coalesce(routine.activity_cursor_sequence, 0), execution.activity_cursor_after),
			    updated_at = ?
			from routine_executions execution
			where routine.workspace_id = ? and routine.id = ?
			  and execution.workspace_id = routine.workspace_id and execution.id = ?
			  and execution.routine_id = routine.id
			  and execution.trigger_kind <> 'GITHUB'
			  and execution.activity_cursor_after is not null
			""".trimIndent(),
			Timestamp.from(now),
			run.workspaceId,
			run.routineId,
			run.routineExecutionId,
		)
	}

	private fun findStepForUpdate(workspaceId: UUID, agentRunId: UUID, stepId: UUID): AgentStepRecord? =
		sqlExecutor.query(
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
	private fun requireSourceVersion(
		workspaceId: UUID,
		agentRunId: UUID,
		sourceScopeId: UUID,
		expectedStatusChangedAt: Instant,
	) {
		val current = sqlExecutor.query(
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
			{ rs, _ -> requireNotNull(rs.getTimestamp("lifecycle_version_at")).toInstant() },
			workspaceId,
			agentRunId,
			sourceScopeId,
		).singleOrNull()
		if (current != expectedStatusChangedAt) throw AgentToolAccessException("SOURCE_CHANGED_DURING_READ")
	}
	private fun insertAdoptedInput(
		workspaceId: UUID,
		agentRunId: UUID,
		input: AgentRunInputRequest,
		now: Instant,
	): AgentRunInputRecord {
		val orderIndex = sqlExecutor.queryForObject(
			"select coalesce(max(order_index), -1) + 1 from agent_run_inputs where workspace_id = ? and agent_run_id = ?",
			Int::class.java,
			workspaceId,
			agentRunId,
		) ?: 0
		val id = uuidGenerator.next()
		sqlExecutor.executeTyped(dsl) { context ->
			context.insertInto(AGENT_RUN_INPUTS)
				.set(AGENT_RUN_INPUTS.ID, id)
				.set(AGENT_RUN_INPUTS.WORKSPACE_ID, workspaceId)
				.set(AGENT_RUN_INPUTS.AGENT_RUN_ID, agentRunId)
				.set(AGENT_RUN_INPUTS.ROUTINE_ID, null as UUID?)
				.set(AGENT_RUN_INPUTS.SOURCE_SCOPE_ID, input.sourceScopeId)
				.set(AGENT_RUN_INPUTS.WRITING_BLOCK_ID, input.writingBlockId)
				.set(AGENT_RUN_INPUTS.SOURCE_PROVIDER, input.sourceProvider)
				.set(AGENT_RUN_INPUTS.SOURCE_KIND, input.sourceKind)
				.set(AGENT_RUN_INPUTS.SOURCE_LABEL, input.sourceLabel)
				.set(AGENT_RUN_INPUTS.INPUT_KIND, AgentRunInputKind.TOOL_RESULT.name)
				.set(AGENT_RUN_INPUTS.ORDER_INDEX, orderIndex)
				.set(AGENT_RUN_INPUTS.ACTIVITY_SEQUENCE, null as Long?)
				.set(AGENT_RUN_INPUTS.SNAPSHOT_TITLE, input.snapshotTitle)
				.set(AGENT_RUN_INPUTS.SNAPSHOT_BODY, input.snapshotBody)
				.set(AGENT_RUN_INPUTS.SNAPSHOT_EXCERPT, input.snapshotExcerpt)
				.set(AGENT_RUN_INPUTS.ORIGINAL_URL, input.originalUrl)
				.set(AGENT_RUN_INPUTS.SOURCE_CREATED_AT, input.sourceCreatedAt?.toOffsetDateTime())
				.set(AGENT_RUN_INPUTS.SOURCE_UPDATED_AT, input.sourceUpdatedAt?.toOffsetDateTime())
				.set(AGENT_RUN_INPUTS.CONTENT_HASH, input.contentHash)
				.set(
					AGENT_RUN_INPUTS.CAPTURED_AT,
					input.capturedAt.takeIf { !it.isAfter(now) }?.toOffsetDateTime() ?: now.toOffsetDateTime(),
				)
				.onConflict(
					AGENT_RUN_INPUTS.WORKSPACE_ID,
					AGENT_RUN_INPUTS.AGENT_RUN_ID,
					AGENT_RUN_INPUTS.SOURCE_SCOPE_ID,
					AGENT_RUN_INPUTS.WRITING_BLOCK_ID,
					AGENT_RUN_INPUTS.CONTENT_HASH,
				)
				.where(AGENT_RUN_INPUTS.INPUT_KIND.eq(AgentRunInputKind.TOOL_RESULT.name))
				.doNothing()
				.execute()
		}
		return queryPersistence.findAdoptedInput(workspaceId, agentRunId, input)
			?: throw RoutineExecutionStateException("Agent read result could not be adopted")
	}
	private fun advanceAndRelease(
		claim: ClaimedAgentRun,
		currentStep: Int?,
		now: Instant,
		nextAttemptAt: Instant? = null,
	) {
		val update = dsl.update(AGENT_RUNS)
			.set(AGENT_RUNS.CLAIMED_BY, null as String?)
			.set(AGENT_RUNS.CLAIMED_AT, null as OffsetDateTime?)
			.set(AGENT_RUNS.NEXT_ATTEMPT_AT, nextAttemptAt?.toOffsetDateTime())
			.set(AGENT_RUNS.TRANSITION_VERSION, AGENT_RUNS.TRANSITION_VERSION.plus(1))
			.set(AGENT_RUNS.UPDATED_AT, now.toOffsetDateTime())
		if (currentStep != null) update.set(AGENT_RUNS.CURRENT_STEP, currentStep)
		val updated = update
			.where(
				AGENT_RUNS.WORKSPACE_ID.eq(claim.workspaceId),
				AGENT_RUNS.ID.eq(claim.agentRunId),
				AGENT_RUNS.CLAIMED_BY.eq(claim.workerId),
				AGENT_RUNS.TRANSITION_VERSION.eq(claim.transitionVersion),
				AGENT_RUNS.STATUS.eq(AgentRunStatus.RUNNING.name),
			)
			.execute()
		if (updated != 1) throw AgentRunClaimLostException()
	}
	private fun terminalizeAgentRun(
		claim: ClaimedAgentRun,
		status: AgentRunStatus,
		errorCode: String?,
		now: Instant,
	): AgentRunRecord = transactionExecutor.execute {
		require(status in setOf(AgentRunStatus.SUCCEEDED, AgentRunStatus.FAILED)) { "Agent terminal status is invalid" }
		if (errorCode != null) require(errorCode.matches(safeErrorCode)) { "Agent error code is invalid" }
		queryPersistence.requireAgentClaim(claim)
		if (status == AgentRunStatus.FAILED) {
			dsl.update(AGENT_STEPS)
				.set(AGENT_STEPS.STATUS, AgentStepStatus.FAILED.name)
				.set(AGENT_STEPS.FAILURE_CODE, requireNotNull(errorCode))
				.set(AGENT_STEPS.FINISHED_AT, AGENT_STEPS.FINISHED_AT.coalesce(now.toOffsetDateTime()))
				.where(
					AGENT_STEPS.WORKSPACE_ID.eq(claim.workspaceId),
					AGENT_STEPS.AGENT_RUN_ID.eq(claim.agentRunId),
					AGENT_STEPS.STATUS.eq(AgentStepStatus.RUNNING.name),
				)
				.execute()
		}
		val updated = dsl.update(AGENT_RUNS)
			.set(AGENT_RUNS.STATUS, status.name)
			.set(AGENT_RUNS.FAILURE_CODE, errorCode)
			.set(AGENT_RUNS.CLAIMED_BY, null as String?)
			.set(AGENT_RUNS.CLAIMED_AT, null as OffsetDateTime?)
			.set(AGENT_RUNS.NEXT_ATTEMPT_AT, null as OffsetDateTime?)
			.set(AGENT_RUNS.FINISHED_AT, now.toOffsetDateTime())
			.set(AGENT_RUNS.TRANSITION_VERSION, AGENT_RUNS.TRANSITION_VERSION.plus(1))
			.set(AGENT_RUNS.UPDATED_AT, now.toOffsetDateTime())
			.where(
				AGENT_RUNS.WORKSPACE_ID.eq(claim.workspaceId),
				AGENT_RUNS.ID.eq(claim.agentRunId),
				AGENT_RUNS.CLAIMED_BY.eq(claim.workerId),
				AGENT_RUNS.TRANSITION_VERSION.eq(claim.transitionVersion),
				AGENT_RUNS.STATUS.eq(AgentRunStatus.RUNNING.name),
			)
			.execute()
		if (updated != 1) throw AgentRunClaimLostException()
		requireNotNull(queryPersistence.findAgentRun(claim.workspaceId, claim.agentRunId))
	}
	private fun failExhaustedStaleAgentRuns(staleBefore: Instant, now: Instant) {
		val exhausted = sqlExecutor.query(
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
			dsl.update(AGENT_STEPS)
				.set(AGENT_STEPS.STATUS, AgentStepStatus.FAILED.name)
				.set(AGENT_STEPS.FAILURE_CODE, "AGENT_RETRY_EXHAUSTED")
				.set(AGENT_STEPS.FINISHED_AT, AGENT_STEPS.FINISHED_AT.coalesce(now.toOffsetDateTime()))
				.where(
					AGENT_STEPS.WORKSPACE_ID.eq(workspaceId),
					AGENT_STEPS.AGENT_RUN_ID.eq(agentRunId),
					AGENT_STEPS.STATUS.eq(AgentStepStatus.RUNNING.name),
				)
				.execute()
			dsl.update(AGENT_RUNS)
				.set(AGENT_RUNS.STATUS, AgentRunStatus.FAILED.name)
				.set(AGENT_RUNS.FAILURE_CODE, "AGENT_RETRY_EXHAUSTED")
				.set(AGENT_RUNS.CLAIMED_BY, null as String?)
				.set(AGENT_RUNS.CLAIMED_AT, null as OffsetDateTime?)
				.set(AGENT_RUNS.NEXT_ATTEMPT_AT, null as OffsetDateTime?)
				.set(AGENT_RUNS.FINISHED_AT, now.toOffsetDateTime())
				.set(AGENT_RUNS.TRANSITION_VERSION, AGENT_RUNS.TRANSITION_VERSION.plus(1))
				.set(AGENT_RUNS.UPDATED_AT, now.toOffsetDateTime())
				.where(
					AGENT_RUNS.WORKSPACE_ID.eq(workspaceId),
					AGENT_RUNS.ID.eq(agentRunId),
					AGENT_RUNS.STATUS.eq(AgentRunStatus.RUNNING.name),
				)
				.execute()
		}
	}
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
