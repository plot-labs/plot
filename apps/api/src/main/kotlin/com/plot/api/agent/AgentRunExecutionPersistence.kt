package com.plot.api.agent


import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.artifact.run.ArtifactRunStatus
import com.plot.api.common.UuidGenerator
import com.plot.api.github.GitHubReleaseReconciliationTrigger
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.TransactionExecutor
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class AgentRunExecutionPersistence(
	private val sqlExecutor: SqlExecutor,
	private val transactionExecutor: TransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val queryPersistence: AgentRunQueryPersistence,
	private val artifactRunPersistence: ArtifactRunPersistence,
	@Lazy private val releaseReconciliation: GitHubReleaseReconciliationTrigger? = null,
	private val clock: Clock? = null,
	private val snapshots: AgentExecutionSnapshotPersistence,
	private val completionProjection: AgentRunCompletionProjection,
) {
	private val safeErrorCode = Regex("[A-Z][A-Z0-9_]{0,99}")
	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	fun recoverStaleAgentRuns(staleBefore: Instant, now: Instant = currentInstant()): Int {
		failExhaustedStaleAgentRuns(staleBefore, now)
		return sqlExecutor.update(
			"""
			update agent_runs
			set claimed_by = null, claimed_at = null,
			    transition_version = transition_version + 1, updated_at = ?
			where status = 'RUNNING' and claimed_by is not null and claimed_at < ?
			  and attempt_count < max_attempts
			""".trimIndent(),
			Timestamp.from(now),
			Timestamp.from(staleBefore),
		)
	}

	fun completeWaitingArtifactHandoff(
		workspaceId: UUID,
		artifactWorkflowRunId: UUID,
		now: Instant = currentInstant(),
	): Boolean = transactionExecutor.execute {
		val agentRunId = sqlExecutor.query(
			"""
			select agent_run_id
			from generation_runs
			where workspace_id = ? and id = ? and agent_run_id is not null
			""".trimIndent(),
			{ rs, _ -> requireNotNull(rs.getObject("agent_run_id", UUID::class.java)) },
			workspaceId,
			artifactWorkflowRunId,
		).firstOrNull() ?: return@execute false
		val run = queryPersistence.findAgentRun(workspaceId, agentRunId) ?: return@execute false
		if (run.status != AgentRunStatus.RUNNING) return@execute false
		val claim = claimRunningAgentRun(run, "artifact-completion-${UUID.randomUUID()}", now)
			?: return@execute false
		val state = artifactRunPersistence.findWorkflowStateByWorkflowRun(workspaceId, artifactWorkflowRunId)
			?: throw IllegalArgumentException("Linked artifact run is unavailable")
		when {
			state.materialized && state.status in setOf(ArtifactRunStatus.READY, ArtifactRunStatus.NEEDS_REVIEW) -> {
				succeedAgentRun(claim, now)
				true
			}
			state.status == ArtifactRunStatus.FAILED -> {
				failAgentRun(claim, "AGENT_ARTIFACT_WORKFLOW_FAILED", now)
				true
			}
			else -> {
				releaseArtifactHandoffClaim(claim, now)
				false
			}
		}
	} ?: false

	/**
	 * Resumes every agent run parked on an artifact workflow that already reached a
	 * terminal state, so a completion that raced with the handoff or was lost to a
	 * restart still finishes the run.
	 */
	fun reconcileWaitingArtifactHandoffs(now: Instant = currentInstant()): Int {
		val waiting = sqlExecutor.query(
			"""
			select workflow.workspace_id, workflow.id
			from generation_runs workflow
			join artifact_runs artifact
			  on artifact.workspace_id = workflow.workspace_id and artifact.id = workflow.artifact_run_id
			join agent_runs run
			  on run.workspace_id = workflow.workspace_id and run.id = workflow.agent_run_id
			where workflow.agent_run_id is not null
			  and artifact.status in ('READY', 'NEEDS_REVIEW', 'FAILED')
			  and run.status = 'RUNNING' and run.claimed_by is null
			""".trimIndent(),
			{ rs, _ ->
				requireNotNull(rs.getObject("workspace_id", UUID::class.java)) to
					requireNotNull(rs.getObject("id", UUID::class.java))
			},
		)
		return waiting.count { (workspaceId, workflowRunId) ->
			completeWaitingArtifactHandoff(workspaceId, workflowRunId, now)
		}
	}

	fun appendStep(
		workspaceId: UUID,
		request: AgentStepRequest,
		now: Instant = currentInstant(),
	): AgentStepRecord = transactionExecutor.execute {
		val id = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into agent_steps (
			  id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
			  tool_name, arguments, result, adopted_input_id, generation_run_id, failure_code,
			  started_at, finished_at, created_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			id, workspaceId, request.agentRunId, request.sequence, request.kind.name, request.status.name,
			request.idempotencyKey.trim(), request.toolName, request.argumentsJson, request.resultJson,
			request.adoptedInputId, request.artifactWorkflowRunId, request.failureCode,
			request.startedAt?.let(Timestamp::from), request.finishedAt?.let(Timestamp::from), Timestamp.from(now),
		)
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
		val updated = sqlExecutor.update(
			"""
			update agent_runs
			set status = 'RUNNING', claimed_by = ?, claimed_at = ?, next_attempt_at = null,
			    started_at = coalesce(started_at, ?), transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ?
			  and status in ('QUEUED', 'RUNNING') and attempt_count < max_attempts
			  and (claimed_by is null or claimed_at < ?)
			""".trimIndent(),
			workerId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
			candidate.first, candidate.second, candidate.third, Timestamp.from(staleBefore),
		)
		if (updated == 1) ClaimedAgentRun(candidate.first, candidate.second, candidate.third + 1, workerId) else null
	}
	fun recordAgentInfrastructureFailure(
		claim: ClaimedAgentRun,
		now: Instant = currentInstant(),
	) {
		transactionExecutor.executeWithoutResult {
			queryPersistence.requireAgentClaim(claim)
			val updated = sqlExecutor.update(
				"""
				update agent_runs
				set attempt_count = attempt_count + 1, failure_code = 'AGENT_INFRASTRUCTURE_FAILURE', updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
				  and status = 'RUNNING' and attempt_count < max_attempts
				""".trimIndent(),
				Timestamp.from(now), claim.workspaceId, claim.agentRunId, claim.workerId, claim.transitionVersion,
			)
			if (updated != 1) throw AgentRunClaimLostException()
		}
	}
	fun beginModelDecision(claim: ClaimedAgentRun, maxModelCalls: Int): AgentRunRecord = transactionExecutor.execute {
		require(maxModelCalls > 0) { "Agent model-call budget must be positive" }
		val run = queryPersistence.requireAgentClaim(claim)
		queryPersistence.requireAllAgentSourcesActiveForUpdate(
			claim.workspaceId,
			claim.agentRunId,
			allowDisconnectedConnection = snapshots.isFrozenReplay(claim.workspaceId, claim.agentRunId),
		)
		if (run.modelCallCount >= maxModelCalls) {
			throw AgentRunBudgetExceededException("AGENT_MODEL_CALL_LIMIT")
		}
		val updated = sqlExecutor.update(
			"""
			update agent_runs set model_call_count = model_call_count + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ? and status = 'RUNNING'
			""".trimIndent(),
			Timestamp.from(currentInstant()), claim.workspaceId, claim.agentRunId, claim.workerId, claim.transitionVersion,
		)
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
		queryPersistence.requireAllAgentSourcesActiveForUpdate(
			claim.workspaceId,
			claim.agentRunId,
			allowDisconnectedConnection = snapshots.isFrozenReplay(claim.workspaceId, claim.agentRunId),
		)
		require(request.agentRunId == claim.agentRunId) { "Agent step belongs to another run" }
		require(request.sequence == run.currentStep) { "Agent step sequence is stale" }
		require(request.status == AgentStepStatus.RUNNING) { "Reserved Agent step must be running" }
		queryPersistence.findStepBySequence(claim.workspaceId, claim.agentRunId, request.sequence)?.let { existing ->
			if (existing.idempotencyKey != request.idempotencyKey || existing.kind != request.kind) {
				throw AgentRunStateException("Agent step idempotency conflict")
			}
			return@execute existing
		}
		if (request.kind == AgentStepKind.READ_TOOL && run.toolCallCount >= maxToolCalls) {
			throw AgentRunBudgetExceededException("AGENT_TOOL_CALL_LIMIT")
		}
		val id = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into agent_steps (
			  id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
			  tool_name, arguments, started_at, created_at
			) values (?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?::jsonb, ?, ?)
			""".trimIndent(),
			id, claim.workspaceId, claim.agentRunId, request.sequence, request.kind.name,
			request.idempotencyKey.trim(), request.toolName, request.argumentsJson, Timestamp.from(now), Timestamp.from(now),
		)
		if (request.kind == AgentStepKind.READ_TOOL) {
			val incremented = sqlExecutor.update(
				"""
				update agent_runs set tool_call_count = tool_call_count + 1, updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
				  and status = 'RUNNING' and tool_call_count < ?
				""".trimIndent(),
				Timestamp.from(now), claim.workspaceId, claim.agentRunId, claim.workerId, claim.transitionVersion, maxToolCalls,
			)
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
			?: throw AgentRunStateException("Agent step was not found")
		require(step.sequence == run.currentStep && step.kind == AgentStepKind.READ_TOOL) {
			"Agent read step is stale"
		}
		require(step.status == AgentStepStatus.RUNNING) { "Agent read step is not running" }
		queryPersistence.requireAllAgentSourcesActiveForUpdate(
			claim.workspaceId,
			claim.agentRunId,
			allowDisconnectedConnection = snapshots.isFrozenReplay(claim.workspaceId, claim.agentRunId),
		)
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

		val stepUpdated = sqlExecutor.update(
			"""
			update agent_steps set status = 'SUCCEEDED', result = ?::jsonb, adopted_input_id = ?, finished_at = ?
			where workspace_id = ? and id = ? and agent_run_id = ? and status = 'RUNNING'
			""".trimIndent(),
			resultJson, adopted?.id, Timestamp.from(now), claim.workspaceId, stepId, claim.agentRunId,
		)
		snapshots.recordTranscriptEntry(
			workspaceId = claim.workspaceId,
			agentRunId = claim.agentRunId,
			callIndex = step.sequence,
			toolName = step.toolName ?: "UNKNOWN",
			argumentsJson = step.argumentsJson,
			resultJson = resultJson,
			adoptedInputHash = adopted?.contentHash,
			now = now,
		)
		advanceAndRelease(claim, run.currentStep + 1, now)
		requireNotNull(queryPersistence.findStep(claim.workspaceId, claim.agentRunId, stepId))
	}
	fun failToolStep(
		claim: ClaimedAgentRun,
		stepId: UUID,
		code: String,
		resultJson: String,
		now: Instant = currentInstant(),
	): AgentStepRecord = transactionExecutor.execute {
		val run = queryPersistence.requireAgentClaim(claim)
		val step = findStepForUpdate(claim.workspaceId, claim.agentRunId, stepId)
			?: throw AgentRunStateException("Agent step was not found")
		require(step.sequence == run.currentStep) { "Agent step is stale" }
		require(step.status == AgentStepStatus.RUNNING) { "Agent step is not running" }
		val stepUpdated = sqlExecutor.update(
			"""
			update agent_steps set status = 'FAILED', failure_code = ?, result = ?::jsonb, finished_at = ?
			where workspace_id = ? and id = ? and agent_run_id = ? and status = 'RUNNING'
			""".trimIndent(),
			code, resultJson, Timestamp.from(now), claim.workspaceId, stepId, claim.agentRunId,
		)
		if (stepUpdated != 1) throw AgentRunClaimLostException()
		snapshots.recordTranscriptEntry(
			workspaceId = claim.workspaceId,
			agentRunId = claim.agentRunId,
			callIndex = step.sequence,
			toolName = step.toolName ?: "UNKNOWN",
			argumentsJson = step.argumentsJson,
			resultJson = resultJson,
			adoptedInputHash = null,
			now = now,
		)
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
			?: throw AgentRunStateException("Agent step was not found")
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
		if (!artifactWorkflowBelongsToAgent) throw AgentRunStateException("ArtifactWorkflow handoff belongs to another Agent run")
		val stepUpdated = sqlExecutor.update(
			"""
			update agent_steps set status = 'SUCCEEDED', generation_run_id = ?, result = ?::jsonb, finished_at = ?
			where workspace_id = ? and id = ? and agent_run_id = ? and status = 'RUNNING'
			""".trimIndent(),
			artifactWorkflowRunId, resultJson, Timestamp.from(now), claim.workspaceId, stepId, claim.agentRunId,
		)
		if (stepUpdated != 1) throw AgentRunClaimLostException()
		advanceAndRelease(claim, run.currentStep + 1, now, nextAttemptAt)
		requireNotNull(queryPersistence.findStep(claim.workspaceId, claim.agentRunId, stepId))
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
		val updated = sqlExecutor.update(
			"""
			update agent_runs
			set status = ?, attempt_count = ?, failure_code = ?, next_attempt_at = ?,
			    claimed_by = null, claimed_at = null, transition_version = transition_version + 1,
			    finished_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ? and status = 'RUNNING'
			""".trimIndent(),
			if (terminal) AgentRunStatus.FAILED.name else AgentRunStatus.QUEUED.name,
			attempts, errorCode, if (terminal) null else Timestamp.from(nextAttemptAt),
			if (terminal) Timestamp.from(now) else null, Timestamp.from(now),
			claim.workspaceId, claim.agentRunId, claim.workerId, claim.transitionVersion,
		)
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
		if (!materialized) throw AgentRunStateException("Agent artifact workflow is not materialized")
		val completed = terminalizeAgentRun(claim, AgentRunStatus.SUCCEEDED, null, now)
		completionProjection.commitSuccessfulInput(run, now)
		completed
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
		sqlExecutor.update(
			"""
			insert into agent_run_inputs (
			  id, workspace_id, agent_run_id, routine_id, source_scope_id, writing_block_id,
			  source_provider, source_kind, source_label, input_kind, order_index, activity_sequence,
			  snapshot_title, snapshot_body, snapshot_excerpt, original_url, source_created_at,
			  source_updated_at, content_hash, captured_at
			) values (?, ?, ?, null, ?, ?, ?, ?, ?, 'TOOL_RESULT', ?, null, ?, ?, ?, ?, ?, ?, ?, ?)
			on conflict (workspace_id, agent_run_id, source_scope_id, writing_block_id, content_hash)
			where input_kind = 'TOOL_RESULT' do nothing
			""".trimIndent(),
			id, workspaceId, agentRunId, input.sourceScopeId, input.writingBlockId,
			input.sourceProvider, input.sourceKind, input.sourceLabel, orderIndex,
			input.snapshotTitle, input.snapshotBody, input.snapshotExcerpt, input.originalUrl,
			input.sourceCreatedAt?.let(Timestamp::from), input.sourceUpdatedAt?.let(Timestamp::from), input.contentHash,
			Timestamp.from(input.capturedAt.takeIf { !it.isAfter(now) } ?: now),
		)
		return queryPersistence.findAdoptedInput(workspaceId, agentRunId, input)
			?: throw AgentRunStateException("Agent read result could not be adopted")
	}
	private fun claimRunningAgentRun(run: AgentRunRecord, workerId: String, now: Instant): ClaimedAgentRun? {
		val updated = sqlExecutor.update(
			"""
			update agent_runs
			set claimed_by = ?, claimed_at = ?, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and status = 'RUNNING'
			  and claimed_by is null and transition_version = ?
			""".trimIndent(),
			workerId, Timestamp.from(now), Timestamp.from(now), run.workspaceId, run.id, run.transitionVersion,
		)
		if (updated != 1) return null
		return ClaimedAgentRun(
			workspaceId = run.workspaceId,
			agentRunId = run.id,
			transitionVersion = run.transitionVersion + 1,
			workerId = workerId,
		)
	}

	private fun releaseArtifactHandoffClaim(claim: ClaimedAgentRun, now: Instant) {
		sqlExecutor.update(
			"""
			update agent_runs
			set claimed_by = null, claimed_at = null, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ? and status = 'RUNNING'
			""".trimIndent(),
			Timestamp.from(now), claim.workspaceId, claim.agentRunId, claim.workerId, claim.transitionVersion,
		)
	}

	private fun advanceAndRelease(
		claim: ClaimedAgentRun,
		currentStep: Int?,
		now: Instant,
		nextAttemptAt: Instant? = null,
	) {
		val updated = sqlExecutor.update(
			"""
			update agent_runs
			set claimed_by = null, claimed_at = null, next_attempt_at = ?,
			    transition_version = transition_version + 1, updated_at = ?,
			    current_step = coalesce(?, current_step)
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ? and status = 'RUNNING'
			""".trimIndent(),
			nextAttemptAt?.let(Timestamp::from), Timestamp.from(now), currentStep,
			claim.workspaceId, claim.agentRunId, claim.workerId, claim.transitionVersion,
		)
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
		val run = queryPersistence.requireAgentClaim(claim)
		if (status == AgentRunStatus.FAILED) {
			sqlExecutor.update(
				"""
				update agent_steps
				set status = 'FAILED', failure_code = ?, finished_at = coalesce(finished_at, ?)
				where workspace_id = ? and agent_run_id = ? and status = 'RUNNING'
				""".trimIndent(),
				requireNotNull(errorCode), Timestamp.from(now), claim.workspaceId, claim.agentRunId,
			)
		}
		val updated = sqlExecutor.update(
			"""
			update agent_runs
			set status = ?, failure_code = ?, claimed_by = null, claimed_at = null, next_attempt_at = null,
			    finished_at = ?, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ? and status = 'RUNNING'
			""".trimIndent(),
			status.name, errorCode, Timestamp.from(now), Timestamp.from(now),
			claim.workspaceId, claim.agentRunId, claim.workerId, claim.transitionVersion,
		)
		if (updated != 1) throw AgentRunClaimLostException()
		completionProjection.deactivateResponse(claim.workspaceId, claim.agentRunId)
		completionProjection.projectTerminal(run, status, errorCode, now)
		val terminal = requireNotNull(queryPersistence.findAgentRun(claim.workspaceId, claim.agentRunId))
		notifyReleaseReconciliationAfterCommit(terminal.workspaceId, terminal.id)
		terminal
	}

	private fun notifyReleaseReconciliationAfterCommit(workspaceId: UUID, agentRunId: UUID) {
		if (
			TransactionSynchronizationManager.isSynchronizationActive() &&
				TransactionSynchronizationManager.isActualTransactionActive()
		) {
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() {
					releaseReconciliation?.afterAgentRunTerminal(workspaceId, agentRunId)
				}
			})
		} else {
			releaseReconciliation?.afterAgentRunTerminal(workspaceId, agentRunId)
		}
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
			sqlExecutor.update(
				"""
				update agent_steps
				set status = 'FAILED', failure_code = 'AGENT_RETRY_EXHAUSTED', finished_at = coalesce(finished_at, ?)
				where workspace_id = ? and agent_run_id = ? and status = 'RUNNING'
				""".trimIndent(),
				Timestamp.from(now), workspaceId, agentRunId,
			)
			sqlExecutor.update(
				"""
				update agent_runs
				set status = 'FAILED', failure_code = 'AGENT_RETRY_EXHAUSTED', claimed_by = null,
				    claimed_at = null, next_attempt_at = null, finished_at = ?,
				    transition_version = transition_version + 1, updated_at = ?
				where workspace_id = ? and id = ? and status = 'RUNNING'
				""".trimIndent(),
				Timestamp.from(now), Timestamp.from(now), workspaceId, agentRunId,
			)
			completionProjection.deactivateResponse(requireNotNull(workspaceId), requireNotNull(agentRunId))
			queryPersistence.findAgentRun(requireNotNull(workspaceId), requireNotNull(agentRunId))?.let { run ->
				completionProjection.projectTerminal(run, AgentRunStatus.FAILED, "AGENT_RETRY_EXHAUSTED", now)
			}
		}
	}
}
