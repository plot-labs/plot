package com.plot.api.artifact.workflow

import com.openai.errors.OpenAIServiceException
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelRole
import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.artifact.run.ArtifactRunStatus
import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import tools.jackson.databind.ObjectMapper

class ArtifactWorkflowExecutionPersistence(
	private val sqlExecutor: JooqSqlExecutor,
	private val objectMapper: ObjectMapper,
	private val transactionExecutor: JooqTransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val artifactRunPersistence: ArtifactRunPersistence,
	private val materializationPersistence: ArtifactWorkflowMaterializationPersistence,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun claimNext(
		workerId: String,
		staleBefore: Instant,
		includeAgentRuns: Boolean = true,
	): ClaimedArtifactWorkflowRun? = transactionExecutor.execute {
		val row = sqlExecutor.query(
			"""
			select workspace_id, id, transition_version
			from generation_runs
			where status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			  and (? or agent_run_id is null)
			  and (next_attempt_at is null or next_attempt_at <= now())
			  and (claimed_by is null or heartbeat_at < ?)
			  and (
			    source_scope_id is null
			    or exists (
			      select 1
			      from source_scopes scope
			      where scope.workspace_id = generation_runs.workspace_id
			        and scope.id = generation_runs.source_scope_id
			        and scope.status = 'ACTIVE'
			    )
			  )
			order by created_at, id
			for update skip locked
			limit 1
			""".trimIndent(),
			{ rs, _ ->
				Triple(
					requireNotNull(rs.getObject(1, UUID::class.java)),
					requireNotNull(rs.getObject(2, UUID::class.java)),
					rs.getLong(3),
				)
			},
			includeAgentRuns,
			Timestamp.from(staleBefore),
		).firstOrNull() ?: return@execute null
		val now = clock.instant()
		val updated = sqlExecutor.update(
			"""
			update generation_runs
			set claimed_by = ?, claimed_at = ?, heartbeat_at = ?,
			    next_attempt_at = null, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ?
			  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			  and (claimed_by is null or heartbeat_at is null or heartbeat_at < ?)
			""".trimIndent(),
			workerId,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
			row.first,
			row.second,
			row.third,
			Timestamp.from(staleBefore),
		)
		if (updated == 1) ClaimedArtifactWorkflowRun(row.first, row.second, row.third + 1, workerId) else null
	}

	fun fenceSourceScope(
		workspaceId: UUID,
		sourceScopeId: UUID,
		now: Instant,
		errorCode: String = "SOURCE_ACCESS_LOST",
	): Int = transactionExecutor.execute {
		require(errorCode.isNotBlank()) { "ArtifactWorkflow fence error code is required" }
		sqlExecutor.update(
			"""
			update model_invocations
			set status = 'FAILED', failure_code = ?, finished_at = ?
			where workspace_id = ? and status = 'RUNNING'
			  and generation_run_id in (
			    select id from generation_runs
			    where workspace_id = ? and source_scope_id = ?
			      and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			  )
			""".trimIndent(),
			errorCode,
			Timestamp.from(now),
			workspaceId,
			workspaceId,
			sourceScopeId,
		)
		sqlExecutor.update(
			"""
			update generation_workflow_steps
			set status = 'FAILED', failure_code = ?, finished_at = ?
			where workspace_id = ? and status = 'RUNNING'
			  and generation_run_id in (
			    select id from generation_runs
			    where workspace_id = ? and source_scope_id = ?
			      and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			  )
			""".trimIndent(),
			errorCode,
			Timestamp.from(now),
			workspaceId,
			workspaceId,
			sourceScopeId,
		)
		sqlExecutor.update(
			"""
			update generation_runs
			set status = 'FAILED', error_code = ?,
			    claimed_by = null, claimed_at = null, heartbeat_at = null,
			    next_attempt_at = null, finished_at = ?,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and source_scope_id = ?
			  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			""".trimIndent(),
			errorCode,
			Timestamp.from(now),
			Timestamp.from(now),
			workspaceId,
			sourceScopeId,
		)
	}

	fun renewClaim(claim: ClaimedArtifactWorkflowRun, now: Instant): Boolean = sqlExecutor.update(
		"""
		update generation_runs
		set heartbeat_at = ?, updated_at = ?
		where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
		  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
		""".trimIndent(),
		Timestamp.from(now),
		Timestamp.from(now),
		claim.workspaceId,
		claim.runId,
		claim.workerId,
		claim.transitionVersion,
	) == 1

	fun beginInvocation(claim: ClaimedArtifactWorkflowRun, role: ModelRole): ModelInvocationLease = transactionExecutor.execute {
		requireClaim(claim)
		val currentStep = sqlExecutor.query(
			"""
			select id, sequence_no
			from generation_workflow_steps
			where workspace_id = ? and generation_run_id = ? and step_kind = ? and status = 'RUNNING'
			order by sequence_no desc
			limit 1
			""".trimIndent(),
			{ rs, _ -> rs.getObject("id", UUID::class.java) to rs.getInt("sequence_no") },
			claim.workspaceId, claim.runId, role.name,
		).firstOrNull()
		val stepId: UUID
		val callIndex: Int
		val attemptNo: Int
		val invocationId = uuidGenerator.next()
		val now = clock.instant()
		if (currentStep == null) {
			val sequence = sqlExecutor.queryForObject(
				"select coalesce(max(sequence_no), -1) + 1 from generation_workflow_steps where workspace_id = ? and generation_run_id = ?",
				Int::class.java, claim.workspaceId, claim.runId,
			) ?: 0
			callIndex = sqlExecutor.queryForObject(
				"select coalesce(max(logical_call_index), -1) + 1 from model_invocations where workspace_id = ? and generation_run_id = ?",
				Int::class.java, claim.workspaceId, claim.runId,
			) ?: 0
			stepId = uuidGenerator.next()
			attemptNo = 1
			val semanticAttempt = sqlExecutor.queryForObject(
				"select semantic_rewrite_attempt from generation_runs where workspace_id = ? and id = ?",
				Int::class.java, claim.workspaceId, claim.runId,
			) ?: 0
			requireExactlyOne(sqlExecutor.update(
				"""
				insert into generation_workflow_steps (id, workspace_id, generation_run_id, step_kind, sequence_no,
				 semantic_attempt, status, started_at, created_at)
				values (?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?)
				""".trimIndent(),
				stepId, claim.workspaceId, claim.runId, role.name, sequence, semanticAttempt,
				Timestamp.from(now), Timestamp.from(now),
			), "ArtifactWorkflow workflow step was not inserted")
		} else {
			stepId = requireNotNull(currentStep.first)
			val invocationSequence = sqlExecutor.queryForMap(
				"""
				select min(logical_call_index) as logical_call_index,
				       coalesce(max(attempt_no), 0) + 1 as attempt_no
				from model_invocations
				where workspace_id = ? and generation_run_id = ? and workflow_step_id = ?
				""".trimIndent(),
				claim.workspaceId,
				claim.runId,
				stepId,
			)
			callIndex = (invocationSequence["logical_call_index"] as Number).toInt()
			attemptNo = (invocationSequence["attempt_no"] as Number).toInt()
		}
		val providerModel = sqlExecutor.queryForMap(
			"select provider, model_name from generation_runs where workspace_id = ? and id = ?",
			claim.workspaceId, claim.runId,
		)
		requireExactlyOne(sqlExecutor.update(
			"""
			insert into model_invocations (id, workspace_id, generation_run_id, workflow_step_id, role,
			 logical_call_index, attempt_no, status, provider, model_name, started_at, created_at)
			values (?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?)
			""".trimIndent(),
			invocationId, claim.workspaceId, claim.runId, stepId, role.name, callIndex, attemptNo,
			providerModel["provider"], providerModel["model_name"], Timestamp.from(now), Timestamp.from(now),
		), "ArtifactWorkflow model invocation was not inserted")
		val visibleStatus = if (role == ModelRole.WRITER) "WRITING" else if (role == ModelRole.REVIEWER) "REVIEWING" else "REWRITING"
		requireExactlyOne(
			sqlExecutor.update(
				"""
				update generation_runs
				set status = ?, started_at = coalesce(started_at, ?), heartbeat_at = ?, updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
				  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
				""".trimIndent(),
				visibleStatus,
				Timestamp.from(now),
				Timestamp.from(now),
				Timestamp.from(now),
				claim.workspaceId,
				claim.runId,
				claim.workerId,
				claim.transitionVersion,
			),
			"ArtifactWorkflow run claim was lost",
		)
		ModelInvocationLease(invocationId, stepId, role, callIndex, attemptNo)
	}

	fun scheduleInvocationRetry(
		claim: ClaimedArtifactWorkflowRun,
		lease: ModelInvocationLease,
		code: String,
		nextAttemptAt: Instant,
		metadata: ModelCallMetadata? = null,
	) {
		transactionExecutor.executeWithoutResult {
			requireClaim(claim)
			val now = clock.instant()
			requireExactlyOne(
				sqlExecutor.update(
					"""
					update model_invocations
					set status = 'FAILED', provider_request_id = ?, result_metadata = ?::jsonb,
					    prompt_token_count = ?, completion_token_count = ?, total_token_count = ?,
					    latency_ms = ?, failure_code = ?, finished_at = ?
					where workspace_id = ? and generation_run_id = ? and id = ? and status = 'RUNNING'
					""".trimIndent(),
					metadata?.responseId,
					objectMapper.writeValueAsString(metadata?.observationAttributes ?: emptyMap<String, String>()),
					metadata?.promptTokens,
					metadata?.completionTokens,
					metadata?.totalTokens,
					metadata?.latency?.toMillis()?.toInt(),
					code,
					Timestamp.from(now),
					claim.workspaceId,
					claim.runId,
					lease.id,
				),
				"ArtifactWorkflow model invocation retry was lost",
			)
			requireExactlyOne(
				sqlExecutor.update(
					"""
					update generation_runs
					set next_attempt_at = ?, transition_version = transition_version + 1,
					    claimed_by = null, claimed_at = null, heartbeat_at = null, updated_at = ?
					where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
					  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
					""".trimIndent(),
					Timestamp.from(nextAttemptAt),
					Timestamp.from(now),
					claim.workspaceId,
					claim.runId,
					claim.workerId,
					claim.transitionVersion,
				),
				"ArtifactWorkflow run claim was lost",
			)
		}
	}

	fun budgetFailureCode(claim: ClaimedArtifactWorkflowRun): String? {
		val row = sqlExecutor.queryForMap(
			"""
			select (budget_snapshot ->> 'maxModelCalls')::integer as max_calls,
			       (budget_snapshot ->> 'maxTotalTokens')::bigint as max_tokens,
			       (budget_snapshot ->> 'maxRunDurationMillis')::bigint as max_duration_ms,
			       created_at
			from generation_runs where workspace_id = ? and id = ? and claimed_by = ?
			""".trimIndent(),
			claim.workspaceId, claim.runId, claim.workerId,
		)
		val calls = sqlExecutor.queryForObject(
			"select count(*) from model_invocations where workspace_id = ? and generation_run_id = ?",
			Int::class.java, claim.workspaceId, claim.runId,
		) ?: 0
		val tokens = sqlExecutor.queryForObject(
			"select coalesce(sum(total_token_count), 0) from model_invocations where workspace_id = ? and generation_run_id = ?",
			Long::class.java, claim.workspaceId, claim.runId,
		) ?: 0L
		val maxCalls = (row["max_calls"] as Number?)?.toInt()
		val maxTokens = (row["max_tokens"] as Number?)?.toLong()
		val maxDuration = (row["max_duration_ms"] as Number?)?.toLong()
		val createdAt = (row["created_at"] as java.sql.Timestamp).toInstant()
		return when {
			maxCalls != null && calls >= maxCalls -> "MODEL_CALL_BUDGET_EXHAUSTED"
			maxTokens != null && tokens >= maxTokens -> "TOKEN_BUDGET_EXHAUSTED"
			maxDuration != null && java.time.Duration.between(createdAt, clock.instant()).toMillis() >= maxDuration -> "TIME_BUDGET_EXHAUSTED"
			else -> null
		}
	}

	fun completeCheckpoint(
		claim: ClaimedArtifactWorkflowRun,
		lease: ModelInvocationLease,
		state: ArtifactWorkflowState,
		metadata: ModelCallMetadata?,
	) {
		transactionExecutor.executeWithoutResult {
			requireClaim(claim)
			val now = clock.instant()
			requireExactlyOne(sqlExecutor.update(
				"""
				update model_invocations set status = 'SUCCEEDED', provider_request_id = ?, result_metadata = ?::jsonb,
				 prompt_token_count = ?, completion_token_count = ?, total_token_count = ?, latency_ms = ?, finished_at = ?
				where workspace_id = ? and generation_run_id = ? and id = ? and status = 'RUNNING'
				""".trimIndent(),
				metadata?.responseId, objectMapper.writeValueAsString(metadata?.observationAttributes ?: emptyMap<String, String>()),
				metadata?.promptTokens, metadata?.completionTokens, metadata?.totalTokens,
				metadata?.latency?.toMillis()?.toInt(), Timestamp.from(now), claim.workspaceId, claim.runId, lease.id,
			), "ArtifactWorkflow model invocation completion was lost")
			requireExactlyOne(
				sqlExecutor.update(
					"""
					update generation_workflow_steps
					set status = 'SUCCEEDED', finished_at = ?
					where workspace_id = ? and generation_run_id = ? and id = ? and status = 'RUNNING'
					""".trimIndent(),
					Timestamp.from(now), claim.workspaceId, claim.runId, lease.stepId,
				),
				"ArtifactWorkflow workflow step completion was lost",
			)
			materializationPersistence.insertCheckpoint(claim.workspaceId, state, state.artifactType, now, lease.stepId)
			if (state.status == ArtifactWorkflowRunStatus.READY || state.status == ArtifactWorkflowRunStatus.NEEDS_REVIEW) {
				materializationPersistence.insertCheckpoint(claim.workspaceId, state, "FINAL_OUTPUT", now)
				materializationPersistence.materializeTerminal(claim.workspaceId, state, now)
			}
			val terminal = state.status in setOf(ArtifactWorkflowRunStatus.READY, ArtifactWorkflowRunStatus.NEEDS_REVIEW, ArtifactWorkflowRunStatus.FAILED)
			val updated = sqlExecutor.update(
				"""
				update generation_runs set status = ?, semantic_rewrite_attempt = ?, transition_version = transition_version + 1,
				 claimed_by = null, claimed_at = null, heartbeat_at = null, error_code = ?,
				 finished_at = case when ? then ? else finished_at end, updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
				  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
				""".trimIndent(),
				state.status.name, state.semanticRewriteAttempt, state.failureCode, terminal, Timestamp.from(now), Timestamp.from(now),
				claim.workspaceId, claim.runId, claim.workerId, claim.transitionVersion,
			)
			check(updated == 1) { "ArtifactWorkflow run claim was lost" }
			artifactRunPersistence.syncWorkflowState(
				workspaceId = claim.workspaceId,
				workflowRunId = claim.runId,
				status = state.status.toArtifactRunStatus(),
				errorCode = state.failureCode,
				now = now,
			)
		}
	}

	fun failCheckpoint(
		claim: ClaimedArtifactWorkflowRun,
		lease: ModelInvocationLease,
		state: ArtifactWorkflowState,
		code: String,
		metadata: ModelCallMetadata? = null,
		failure: Throwable? = null,
	) {
		transactionExecutor.executeWithoutResult {
			requireClaim(claim)
			val now = clock.instant()
			requireExactlyOne(sqlExecutor.update(
				"""
				update model_invocations set status = 'FAILED', provider_request_id = ?, result_metadata = ?::jsonb,
				 prompt_token_count = ?, completion_token_count = ?, total_token_count = ?, latency_ms = ?, failure_code = ?, failure_detail = ?::jsonb, finished_at = ?
				where workspace_id = ? and generation_run_id = ? and id = ? and status = 'RUNNING'
				""".trimIndent(),
				metadata?.responseId, objectMapper.writeValueAsString(metadata?.observationAttributes ?: emptyMap<String, String>()),
				metadata?.promptTokens, metadata?.completionTokens, metadata?.totalTokens, metadata?.latency?.toMillis()?.toInt(),
				code, failureDetailJson(failure), Timestamp.from(now), claim.workspaceId, claim.runId, lease.id,
			), "ArtifactWorkflow model invocation failure was lost")
			requireExactlyOne(
				sqlExecutor.update(
					"""
					update generation_workflow_steps
					set status = 'FAILED', failure_code = ?, finished_at = ?
					where workspace_id = ? and generation_run_id = ? and id = ? and status = 'RUNNING'
					""".trimIndent(),
					code, Timestamp.from(now), claim.workspaceId, claim.runId, lease.stepId,
				),
				"ArtifactWorkflow workflow step failure was lost",
			)
			failClaimedRun(claim, state, code, now, lease.stepId)
		}
	}

	fun failClaim(claim: ClaimedArtifactWorkflowRun, state: ArtifactWorkflowState, code: String) {
		transactionExecutor.executeWithoutResult {
			requireClaim(claim)
			failClaimedRun(claim, state, code, clock.instant())
		}
	}

	private fun failClaimedRun(
		claim: ClaimedArtifactWorkflowRun,
		state: ArtifactWorkflowState,
		code: String,
		now: Instant,
		stepId: UUID? = null,
	) {
		val failed = state.asFailure(code)
		materializationPersistence.insertCheckpoint(claim.workspaceId, failed, "FINAL_OUTPUT", now, stepId)
		if (failed.status == ArtifactWorkflowRunStatus.NEEDS_REVIEW) materializationPersistence.materializeTerminal(claim.workspaceId, failed, now)
		val updated = sqlExecutor.update(
			"""
			update generation_runs set status = ?, error_code = ?, transition_version = transition_version + 1,
			 claimed_by = null, claimed_at = null, heartbeat_at = null, finished_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
			  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			""".trimIndent(),
			failed.status.name, code, Timestamp.from(now), Timestamp.from(now),
			claim.workspaceId, claim.runId, claim.workerId, claim.transitionVersion,
		)
		check(updated == 1) { "ArtifactWorkflow run claim was lost" }
		artifactRunPersistence.syncWorkflowState(
			workspaceId = claim.workspaceId,
			workflowRunId = claim.runId,
			status = failed.status.toArtifactRunStatus(),
			errorCode = code,
			now = now,
		)
	}
	private fun failureDetailJson(failure: Throwable?): String? = failure?.let {
		val chain = generateSequence(it) { f -> f.cause?.takeIf { c -> c !== f } }
			.take(6)
			.map { f ->
				buildMap {
					put("type", f::class.simpleName.orEmpty())
					put("message", f.message.orEmpty().take(500))
					if (f is OpenAIServiceException) {
						put("statusCode", f.statusCode().toString())
						put("body", runCatching { f.body().toString() }.getOrDefault("").take(2_000))
					}
				}
			}
			.toList()
		objectMapper.writeValueAsString(mapOf("chain" to chain))
	}

	private fun requireClaim(claim: ClaimedArtifactWorkflowRun) {
		val ownedRun = sqlExecutor.query(
			"""
			select id
			from generation_runs
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
			  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			for update
			""".trimIndent(),
			{ rs, _ -> rs.getObject("id", UUID::class.java) },
			claim.workspaceId,
			claim.runId,
			claim.workerId,
			claim.transitionVersion,
		).singleOrNull()
		check(ownedRun != null) { "ArtifactWorkflow run claim was lost" }
	}

	private fun requireExactlyOne(updated: Int, message: String) {
		check(updated == 1) { message }
	}
}

private fun ArtifactWorkflowState.asFailure(code: String): ArtifactWorkflowState = copy(
	status = if (reviews.isEmpty()) ArtifactWorkflowRunStatus.FAILED else ArtifactWorkflowRunStatus.NEEDS_REVIEW,
	failureCode = code,
)

private fun ArtifactWorkflowRunStatus.toArtifactRunStatus(): ArtifactRunStatus = when (this) {
	ArtifactWorkflowRunStatus.QUEUED -> ArtifactRunStatus.QUEUED
	ArtifactWorkflowRunStatus.WRITING -> ArtifactRunStatus.WRITING
	ArtifactWorkflowRunStatus.REVIEWING -> ArtifactRunStatus.REVIEWING
	ArtifactWorkflowRunStatus.REWRITING -> ArtifactRunStatus.REWRITING
	ArtifactWorkflowRunStatus.READY -> ArtifactRunStatus.READY
	ArtifactWorkflowRunStatus.NEEDS_REVIEW -> ArtifactRunStatus.NEEDS_REVIEW
	ArtifactWorkflowRunStatus.FAILED -> ArtifactRunStatus.FAILED
}

private val ArtifactWorkflowState.artifactType: String
	get() = when (artifacts.lastOrNull()?.kind) {
		WorkflowArtifactKind.WRITER_OUTPUT -> "WRITER_OUTPUT"
		WorkflowArtifactKind.REVIEWER_OUTPUT, WorkflowArtifactKind.CONFLICT -> "REVIEWER_OUTPUT"
		WorkflowArtifactKind.REWRITER_OUTPUT -> "REWRITER_OUTPUT"
		null -> "FINAL_OUTPUT"
	}
