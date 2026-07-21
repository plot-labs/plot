package com.plot.api.generation

import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelRole
import com.plot.api.common.JdbcTime.timestamp
import com.plot.api.common.UuidGenerator
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.SentenceArtifact
import com.plot.api.generation.dto.GenerationModelTimingResponse
import com.plot.api.generation.dto.GenerationRunTimingResponse
import com.plot.api.generation.dto.GenerationStepTimingResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

data class GenerationRunReservation(
	val workspaceId: UUID,
	val createdByUserId: UUID,
	val sourceScopeId: UUID?,
	val idempotencyKey: String,
	val requestFingerprint: String,
	val state: GenerationWorkflowState,
	val provider: String,
	val modelName: String,
	val budgetJson: String,
)

data class ClaimedGenerationRun(
	val workspaceId: UUID,
	val runId: UUID,
	val transitionVersion: Long,
	val workerId: String,
)

data class ModelInvocationLease(
	val id: UUID,
	val stepId: UUID,
	val role: ModelRole,
	val logicalCallIndex: Int,
)

class GenerationPersistence(
	private val jdbcTemplate: JdbcTemplate,
	private val objectMapper: ObjectMapper,
	private val transactionTemplate: TransactionTemplate,
	private val uuidGenerator: UuidGenerator,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun findIdempotentRun(
		workspaceId: UUID,
		createdByUserId: UUID,
		idempotencyKey: String,
		requestFingerprint: String,
	): GenerationWorkflowState? {
		val existing = jdbcTemplate.query(
			"select id, request_fingerprint from generation_runs where workspace_id = ? and created_by_user_id = ? and idempotency_key = ?",
			{ rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) },
			workspaceId, createdByUserId, idempotencyKey,
		).firstOrNull() ?: return null
		if (existing.second != requestFingerprint) throw GenerationIdempotencyConflictException()
		return loadState(workspaceId, existing.first)
	}

	fun createRun(reservation: GenerationRunReservation): GenerationWorkflowState = transactionTemplate.execute {
		val existing = jdbcTemplate.query(
			"select id, request_fingerprint from generation_runs where workspace_id = ? and created_by_user_id = ? and idempotency_key = ?",
			{ rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) },
			reservation.workspaceId, reservation.createdByUserId, reservation.idempotencyKey,
		).firstOrNull()
		if (existing != null) {
			if (existing.second != reservation.requestFingerprint) throw GenerationIdempotencyConflictException()
			return@execute loadState(reservation.workspaceId, existing.first)
		}
		val now = clock.instant()
		val inserted = jdbcTemplate.update(
			"""
			insert into generation_runs (
			 id, workspace_id, source_scope_id, created_by_user_id, idempotency_key, request_fingerprint,
			 status, workflow_version, prompt_version, output_schema_version, budget_version, provider,
			 model_name, budget_snapshot, user_instruction, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, 'QUEUED', 'fixed-v1', 'changelog-v8', 'generation-v5',
			 'budget-v1', ?, ?, ?::jsonb, ?, ?, ?)
			on conflict (workspace_id, created_by_user_id, idempotency_key) do nothing
			""".trimIndent(),
			reservation.state.runId, reservation.workspaceId, reservation.sourceScopeId,
			reservation.createdByUserId, reservation.idempotencyKey, reservation.requestFingerprint,
			reservation.provider, reservation.modelName, reservation.budgetJson, reservation.state.instruction,
			timestamp(now), timestamp(now),
		)
		if (inserted == 0) {
			val raced = jdbcTemplate.query(
				"select id, request_fingerprint from generation_runs where workspace_id = ? and created_by_user_id = ? and idempotency_key = ?",
				{ rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) },
				reservation.workspaceId, reservation.createdByUserId, reservation.idempotencyKey,
			).single()
			if (raced.second != reservation.requestFingerprint) throw GenerationIdempotencyConflictException()
			return@execute loadState(reservation.workspaceId, raced.first)
		}
		reservation.state.evidence.forEach { insertEvidence(reservation.workspaceId, it) }
		insertCheckpoint(reservation.workspaceId, reservation.state, "EVIDENCE_SET", now)
		reservation.state
	}

	fun claimNext(workerId: String, staleBefore: Instant): ClaimedGenerationRun? = transactionTemplate.execute {
		val row = jdbcTemplate.query(
			"""
			select workspace_id, id, transition_version
			from generation_runs
			where status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			  and (next_attempt_at is null or next_attempt_at <= now())
			  and (claimed_by is null or heartbeat_at < ?)
			order by created_at, id
			for update skip locked
			limit 1
			""".trimIndent(),
			{ rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getLong(3)) },
			timestamp(staleBefore),
		).firstOrNull() ?: return@execute null
		val now = clock.instant()
		val updated = jdbcTemplate.update(
			"""
			update generation_runs set claimed_by = ?, claimed_at = ?, heartbeat_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and transition_version = ?
			  and (claimed_by is null or heartbeat_at < ?)
			""".trimIndent(),
			workerId, timestamp(now), timestamp(now), timestamp(now), row.first, row.second, row.third, timestamp(staleBefore),
		)
		if (updated == 1) ClaimedGenerationRun(row.first, row.second, row.third, workerId) else null
	}

	fun beginInvocation(claim: ClaimedGenerationRun, role: ModelRole): ModelInvocationLease = transactionTemplate.execute {
		requireClaim(claim)
		val existing = jdbcTemplate.query(
			"""
			select mi.id, mi.workflow_step_id, mi.logical_call_index
			from model_invocations mi
			where mi.workspace_id = ? and mi.generation_run_id = ? and mi.role = ? and mi.status = 'RUNNING'
			order by mi.logical_call_index desc limit 1
			""".trimIndent(),
			{ rs, _ -> ModelInvocationLease(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), role, rs.getInt(3)) },
			claim.workspaceId, claim.runId, role.name,
		).firstOrNull()
		if (existing != null) return@execute existing
		val sequence = jdbcTemplate.queryForObject(
			"select coalesce(max(sequence_no), -1) + 1 from generation_workflow_steps where workspace_id = ? and generation_run_id = ?",
			Int::class.java, claim.workspaceId, claim.runId,
		) ?: 0
		val callIndex = jdbcTemplate.queryForObject(
			"select coalesce(max(logical_call_index), -1) + 1 from model_invocations where workspace_id = ? and generation_run_id = ?",
			Int::class.java, claim.workspaceId, claim.runId,
		) ?: 0
		val stepId = uuidGenerator.next()
		val invocationId = uuidGenerator.next()
		val now = clock.instant()
		val attempt = jdbcTemplate.queryForObject(
			"select semantic_rewrite_attempt from generation_runs where workspace_id = ? and id = ?",
			Int::class.java, claim.workspaceId, claim.runId,
		) ?: 0
		jdbcTemplate.update(
			"""
			insert into generation_workflow_steps (id, workspace_id, generation_run_id, step_kind, sequence_no,
			 semantic_attempt, status, started_at, created_at)
			values (?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?)
			""".trimIndent(),
			stepId, claim.workspaceId, claim.runId, role.name, sequence, attempt, timestamp(now), timestamp(now),
		)
		val providerModel = jdbcTemplate.queryForMap(
			"select provider, model_name from generation_runs where workspace_id = ? and id = ?",
			claim.workspaceId, claim.runId,
		)
		jdbcTemplate.update(
			"""
			insert into model_invocations (id, workspace_id, generation_run_id, workflow_step_id, role,
			 logical_call_index, status, provider, model_name, started_at, created_at)
			values (?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?)
			""".trimIndent(),
			invocationId, claim.workspaceId, claim.runId, stepId, role.name, callIndex,
			providerModel["provider"], providerModel["model_name"], timestamp(now), timestamp(now),
		)
		val visibleStatus = if (role == ModelRole.WRITER) "WRITING" else if (role == ModelRole.REVIEWER) "REVIEWING" else "REWRITING"
		jdbcTemplate.update(
			"update generation_runs set status = ?, started_at = coalesce(started_at, ?), heartbeat_at = ?, updated_at = ? where workspace_id = ? and id = ? and claimed_by = ?",
			visibleStatus, timestamp(now), timestamp(now), timestamp(now), claim.workspaceId, claim.runId, claim.workerId,
		)
		ModelInvocationLease(invocationId, stepId, role, callIndex)
	}

	fun budgetFailureCode(claim: ClaimedGenerationRun): String? {
		val row = jdbcTemplate.queryForMap(
			"""
			select (budget_snapshot ->> 'maxModelCalls')::integer as max_calls,
			       (budget_snapshot ->> 'maxTotalTokens')::bigint as max_tokens,
			       (budget_snapshot ->> 'maxRunDurationMillis')::bigint as max_duration_ms,
			       created_at
			from generation_runs where workspace_id = ? and id = ? and claimed_by = ?
			""".trimIndent(),
			claim.workspaceId, claim.runId, claim.workerId,
		)
		val calls = jdbcTemplate.queryForObject(
			"select count(*) from model_invocations where workspace_id = ? and generation_run_id = ?",
			Int::class.java, claim.workspaceId, claim.runId,
		) ?: 0
		val tokens = jdbcTemplate.queryForObject(
			"select coalesce(sum(total_token_count), 0) from model_invocations where workspace_id = ? and generation_run_id = ? and status = 'SUCCEEDED'",
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
		claim: ClaimedGenerationRun,
		lease: ModelInvocationLease,
		state: GenerationWorkflowState,
		metadata: ModelCallMetadata?,
	) {
		transactionTemplate.executeWithoutResult {
			requireClaim(claim)
			val now = clock.instant()
			jdbcTemplate.update(
				"""
				update model_invocations set status = 'SUCCEEDED', provider_request_id = ?, result_metadata = ?::jsonb,
				 prompt_token_count = ?, completion_token_count = ?, total_token_count = ?, latency_ms = ?, finished_at = ?
				where workspace_id = ? and id = ? and status = 'RUNNING'
				""".trimIndent(),
				metadata?.responseId, objectMapper.writeValueAsString(metadata?.observationAttributes ?: emptyMap<String, String>()),
				metadata?.promptTokens, metadata?.completionTokens, metadata?.totalTokens,
				metadata?.latency?.toMillis()?.toInt(), timestamp(now), claim.workspaceId, lease.id,
			)
			jdbcTemplate.update(
				"update generation_workflow_steps set status = 'SUCCEEDED', finished_at = ? where workspace_id = ? and id = ? and status = 'RUNNING'",
				timestamp(now), claim.workspaceId, lease.stepId,
			)
			insertCheckpoint(claim.workspaceId, state, state.artifactType, now, lease.stepId)
			if (state.status == GenerationRunStatus.READY || state.status == GenerationRunStatus.NEEDS_REVIEW) {
				insertCheckpoint(claim.workspaceId, state, "FINAL_OUTPUT", now)
				materializeTerminal(claim.workspaceId, state, now)
			}
			val terminal = state.status in setOf(GenerationRunStatus.READY, GenerationRunStatus.NEEDS_REVIEW, GenerationRunStatus.FAILED)
			val updated = jdbcTemplate.update(
				"""
				update generation_runs set status = ?, semantic_rewrite_attempt = ?, transition_version = transition_version + 1,
				 claimed_by = null, claimed_at = null, heartbeat_at = null, error_code = ?,
				 finished_at = case when ? then ? else finished_at end, updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ?
				""".trimIndent(),
				state.status.name, state.semanticRewriteAttempt, state.failureCode, terminal, timestamp(now), timestamp(now),
				claim.workspaceId, claim.runId, claim.workerId,
			)
			check(updated == 1) { "Generation run claim was lost" }
		}
	}

	fun failCheckpoint(
		claim: ClaimedGenerationRun,
		lease: ModelInvocationLease,
		state: GenerationWorkflowState,
		code: String,
		metadata: ModelCallMetadata? = null,
	) {
		transactionTemplate.executeWithoutResult {
			requireClaim(claim)
			val now = clock.instant()
			jdbcTemplate.update(
				"""
				update model_invocations set status = 'FAILED', provider_request_id = ?, result_metadata = ?::jsonb,
				 prompt_token_count = ?, completion_token_count = ?, total_token_count = ?, latency_ms = ?, failure_code = ?, finished_at = ?
				where workspace_id = ? and id = ? and status = 'RUNNING'
				""".trimIndent(),
				metadata?.responseId, objectMapper.writeValueAsString(metadata?.observationAttributes ?: emptyMap<String, String>()),
				metadata?.promptTokens, metadata?.completionTokens, metadata?.totalTokens, metadata?.latency?.toMillis()?.toInt(),
				code, timestamp(now), claim.workspaceId, lease.id,
			)
			jdbcTemplate.update(
				"update generation_workflow_steps set status = 'FAILED', failure_code = ?, finished_at = ? where workspace_id = ? and id = ? and status = 'RUNNING'",
				code, timestamp(now), claim.workspaceId, lease.stepId,
			)
			val failed = state.asFailure(code)
			insertCheckpoint(claim.workspaceId, failed, "FINAL_OUTPUT", now, lease.stepId)
			if (failed.status == GenerationRunStatus.NEEDS_REVIEW) materializeTerminal(claim.workspaceId, failed, now)
			jdbcTemplate.update(
				"""
				update generation_runs set status = ?, error_code = ?, transition_version = transition_version + 1,
				 claimed_by = null, claimed_at = null, heartbeat_at = null, finished_at = ?, updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ?
				""".trimIndent(),
				failed.status.name, code, timestamp(now), timestamp(now), claim.workspaceId, claim.runId, claim.workerId,
			)
		}
	}

	fun failClaim(claim: ClaimedGenerationRun, state: GenerationWorkflowState, code: String) {
		transactionTemplate.executeWithoutResult {
			requireClaim(claim)
			val now = clock.instant()
			val failed = state.asFailure(code)
			insertCheckpoint(claim.workspaceId, failed, "FINAL_OUTPUT", now)
			if (failed.status == GenerationRunStatus.NEEDS_REVIEW) materializeTerminal(claim.workspaceId, failed, now)
			jdbcTemplate.update(
				"""
				update generation_runs set status = ?, error_code = ?, transition_version = transition_version + 1,
				 claimed_by = null, claimed_at = null, heartbeat_at = null, finished_at = ?, updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ?
				""".trimIndent(),
				failed.status.name, code, timestamp(now), timestamp(now), claim.workspaceId, claim.runId, claim.workerId,
			)
		}
	}

	fun loadState(workspaceId: UUID, runId: UUID): GenerationWorkflowState {
		val payload = jdbcTemplate.query(
			"select payload::text from generation_artifacts where workspace_id = ? and generation_run_id = ? order by sequence_no desc limit 1",
			{ rs, _ -> rs.getString(1) }, workspaceId, runId,
		).firstOrNull() ?: throw GenerationRunNotFoundException(runId)
		return objectMapper.readValue(payload, GenerationWorkflowState::class.java)
	}

	fun loadTiming(workspaceId: UUID, runId: UUID): GenerationRunTimingResponse {
		val run = jdbcTemplate.query(
			"""
			select gr.created_at, gr.started_at, gr.finished_at, gr.model_name,
			       coalesce(sum(case when mi.status = 'SUCCEEDED' then coalesce(mi.total_token_count, 0) else 0 end), 0),
			       coalesce(sum(case when mi.status = 'SUCCEEDED' then coalesce(mi.latency_ms, 0) else 0 end), 0)
			from generation_runs gr
			left join model_invocations mi on mi.workspace_id = gr.workspace_id and mi.generation_run_id = gr.id
			where gr.workspace_id = ? and gr.id = ?
			group by gr.created_at, gr.started_at, gr.finished_at, gr.model_name
			""".trimIndent(),
			{ rs, _ ->
				RunTimingRow(
					rs.getTimestamp("created_at").toInstant(),
					rs.getTimestamp("started_at")?.toInstant(),
					rs.getTimestamp("finished_at")?.toInstant(),
					rs.getString("model_name"),
					rs.getLong(5),
					rs.getLong(6),
				)
			},
			workspaceId, runId,
		).firstOrNull() ?: throw GenerationRunNotFoundException(runId)

		val steps = jdbcTemplate.query(
			"""
			select step_kind, sequence_no, status, started_at, finished_at, failure_code
			from generation_workflow_steps
			where workspace_id = ? and generation_run_id = ?
			order by sequence_no
			""".trimIndent(),
			{ rs, _ ->
				val startedAt = rs.getTimestamp("started_at").toInstant()
				val finishedAt = rs.getTimestamp("finished_at")?.toInstant()
				GenerationStepTimingResponse(
					kind = rs.getString("step_kind"),
					sequence = rs.getInt("sequence_no"),
					status = rs.getString("status"),
					startedAt = startedAt,
					finishedAt = finishedAt,
					durationMs = finishedAt?.let { Duration.between(startedAt, it).toMillis().coerceAtLeast(0) },
					failureCode = rs.getString("failure_code"),
				)
			},
			workspaceId, runId,
		)

		return GenerationRunTimingResponse(
			createdAt = run.createdAt,
			startedAt = run.startedAt,
			finishedAt = run.finishedAt,
			steps = steps,
			model = GenerationModelTimingResponse(run.modelName, run.totalTokens, run.totalLatencyMs),
		)
	}

	fun recoverStaleClaims(staleBefore: Instant): Int = jdbcTemplate.update(
		"""
		update generation_runs set claimed_by = null, claimed_at = null, heartbeat_at = null, updated_at = ?
		where claimed_by is not null and heartbeat_at < ?
		  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
		""".trimIndent(),
		timestamp(clock.instant()), timestamp(staleBefore),
	)

	private fun insertEvidence(workspaceId: UUID, evidence: EvidenceSnapshot) {
		jdbcTemplate.update(
			"""
			insert into generation_inputs (id, workspace_id, generation_run_id, writing_block_id, order_index,
			 source_provider, source_kind, source_label, snapshot_title, snapshot_body, snapshot_excerpt,
			 original_url, source_created_at, source_updated_at, content_hash, captured_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			evidence.id, workspaceId, evidence.generationRunId, evidence.writingBlockId, evidence.orderIndex,
			evidence.sourceProvider.name, evidence.sourceKind, evidence.sourceLabel, evidence.snapshotTitle,
			evidence.snapshotBody, evidence.snapshotExcerpt, evidence.originalUrl,
			evidence.sourceCreatedAt?.let(::timestamp), evidence.sourceUpdatedAt?.let(::timestamp),
			evidence.contentHash, timestamp(evidence.capturedAt),
		)
	}

	private fun insertCheckpoint(workspaceId: UUID, state: GenerationWorkflowState, type: String, now: Instant, stepId: UUID? = null) {
		val version = jdbcTemplate.queryForObject(
			"select coalesce(max(artifact_version), 0) + 1 from generation_artifacts where workspace_id = ? and generation_run_id = ? and artifact_type = ?",
			Int::class.java, workspaceId, state.runId, type,
		) ?: 1
		val sequence = jdbcTemplate.queryForObject(
			"select coalesce(max(sequence_no), -1) + 1 from generation_artifacts where workspace_id = ? and generation_run_id = ?",
			Int::class.java, workspaceId, state.runId,
		) ?: 0
		jdbcTemplate.update(
			"insert into generation_artifacts (id, workspace_id, generation_run_id, workflow_step_id, artifact_type, artifact_version, sequence_no, payload, created_at) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
			uuidGenerator.next(), workspaceId, state.runId, stepId, type, version, sequence,
			objectMapper.writeValueAsString(state), timestamp(now),
		)
	}

	private fun materializeTerminal(workspaceId: UUID, state: GenerationWorkflowState, now: Instant, userModifiedBy: UUID? = null) {
		if (jdbcTemplate.queryForObject("select count(*) from content_packs where workspace_id = ? and generation_run_id = ?", Int::class.java, workspaceId, state.runId)!! > 0) return
		val packId = uuidGenerator.next()
		val variantId = uuidGenerator.next()
		val status = if (state.status == GenerationRunStatus.READY) "READY" else "NEEDS_REVIEW"
		jdbcTemplate.update(
			"insert into content_packs (id, workspace_id, generation_run_id, title, status, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?)",
			packId, workspaceId, state.runId, state.sentences.firstOrNull()?.body?.take(120), status, timestamp(now), timestamp(now),
		)
		jdbcTemplate.update(
			"insert into content_variants (id, workspace_id, generation_run_id, content_pack_id, variant_index, status, created_at, updated_at) values (?, ?, ?, ?, 0, ?, ?, ?)",
			variantId, workspaceId, state.runId, packId, status, timestamp(now), timestamp(now),
		)
		val revisions = state.artifacts.flatMap { it.sentences }.plus(state.sentences)
			.distinctBy { it.revisionId }.groupBy { it.id }
		state.sentences.sortedBy { it.orderIndex }.forEach { current ->
			jdbcTemplate.update(
				"insert into content_variant_sentences (id, workspace_id, generation_run_id, content_variant_id, stable_key, order_index, created_at) values (?, ?, ?, ?, ?, ?, ?)",
				current.id, workspaceId, state.runId, variantId, current.id.toString(), current.orderIndex, timestamp(now),
			)
			revisions.getValue(current.id).sortedBy { it.revisionNumber }.forEach { revision ->
				jdbcTemplate.update(
					"""
					insert into content_variant_sentence_revisions (id, workspace_id, generation_run_id, content_variant_id,
					 sentence_id, revision_no, origin, body, is_current, created_by_user_id, created_at)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""".trimIndent(),
					revision.revisionId, workspaceId, state.runId, variantId, current.id, revision.revisionNumber,
					revision.origin.name, revision.body, revision.revisionId == current.revisionId,
					userModifiedBy.takeIf { revision.origin.name == "USER_MODIFIED" }, timestamp(now),
				)
			}
		}
		val reviewArtifacts = state.artifacts.filter { it.kind == WorkflowArtifactKind.REVIEWER_OUTPUT }
		val materializedSentenceIds = state.sentences.mapTo(mutableSetOf()) { it.id }
		reviewArtifacts.forEachIndexed { reviewIndex, artifact ->
			artifact.reviews.filter { it.sentenceId in materializedSentenceIds }.forEach { review ->
				val sentence = artifact.sentences.single { it.id == review.sentenceId }
				jdbcTemplate.update(
					"insert into sentence_evaluations (id, workspace_id, generation_run_id, sentence_id, sentence_revision_id, review_attempt, verdict, reason, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
					uuidGenerator.next(), workspaceId, state.runId, sentence.id, sentence.revisionId, reviewIndex + 1,
					review.verdict.name, review.reason, timestamp(now),
				)
			}
		}
		state.reviews.filter { it.verdict == ReviewVerdict.SUPPORTED }.forEach { review ->
			val sentence = state.sentences.single { it.id == review.sentenceId }
			review.evidenceIds.forEachIndexed { citationIndex, evidenceId ->
				jdbcTemplate.update(
					"insert into sentence_citations (id, workspace_id, generation_run_id, content_variant_id, sentence_id, sentence_revision_id, generation_input_id, citation_order, status, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
					uuidGenerator.next(), workspaceId, state.runId, variantId, sentence.id, sentence.revisionId,
					evidenceId, citationIndex, timestamp(now),
				)
			}
		}
	}

	private fun requireClaim(claim: ClaimedGenerationRun) {
		val count = jdbcTemplate.queryForObject(
			"select count(*) from generation_runs where workspace_id = ? and id = ? and claimed_by = ?",
			Int::class.java, claim.workspaceId, claim.runId, claim.workerId,
		) ?: 0
		check(count == 1) { "Generation run claim was lost" }
	}
}

private data class RunTimingRow(
	val createdAt: Instant,
	val startedAt: Instant?,
	val finishedAt: Instant?,
	val modelName: String,
	val totalTokens: Long,
	val totalLatencyMs: Long,
)

private fun GenerationWorkflowState.asFailure(code: String): GenerationWorkflowState = copy(
	status = if (reviews.isEmpty()) GenerationRunStatus.FAILED else GenerationRunStatus.NEEDS_REVIEW,
	failureCode = code,
)

class GenerationIdempotencyConflictException : IllegalStateException("Idempotency key was reused with different inputs")
class GenerationRunNotFoundException(val runId: UUID) : IllegalStateException("Generation run not found")

private val GenerationWorkflowState.artifactType: String
	get() = when (artifacts.lastOrNull()?.kind) {
		WorkflowArtifactKind.WRITER_OUTPUT -> "WRITER_OUTPUT"
		WorkflowArtifactKind.REVIEWER_OUTPUT, WorkflowArtifactKind.CONFLICT -> "REVIEWER_OUTPUT"
		WorkflowArtifactKind.REWRITER_OUTPUT -> "REWRITER_OUTPUT"
		WorkflowArtifactKind.CONFLICT_DECISION -> "CONFLICT_DECISION"
		null -> "FINAL_OUTPUT"
	}
