package com.plot.api.artifact.workflow

import com.plot.api.TestcontainersConfiguration
import com.plot.api.ai.provider.ArtifactWorkflowModelException
import com.plot.api.ai.provider.ArtifactWorkflowModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelCallResult
import com.plot.api.ai.provider.ModelFailureCode
import com.plot.api.ai.provider.ModelRole
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.dev.DevContext
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ReviewVerdict
import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.SentenceReview
import com.plot.api.artifact.workflow.model.SourceProvider
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import com.plot.api.artifact.workflow.model.WriterOutput
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistry
import com.plot.api.artifact.workflow.model.WriterSentence
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArtifactWorkflowPhysicalAttemptIntegrationTest {
	@Autowired private lateinit var persistence: ArtifactWorkflowPersistence
	@Autowired private lateinit var workflow: ArtifactWorkflowService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	@BeforeEach
	fun isolateArtifactWorkflowQueue() {
		jdbcTemplate.update(
			"""
			update generation_runs
			set status = 'FAILED', error_code = 'TEST_ISOLATION',
			    claimed_by = null, claimed_at = null, heartbeat_at = null,
			    finished_at = coalesce(finished_at, greatest(now(), created_at)), updated_at = now()
			where status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			""".trimIndent(),
		)
	}

	@Test
	fun transientFailureCreatesANewAttemptForTheSameLogicalStep() {
		val state = reserve("transient-then-success")
		val gateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 1)
		val worker = ArtifactWorkflowRunWorker(persistence, workflow, gateway, workerId = "retry-worker")

		assertEquals(true, worker.processOne())
		assertEquals("WRITING", runStatus(state.runId))
		assertEquals(
			listOf(InvocationRow("WRITER", 0, 1, "FAILED", "PROVIDER_UNAVAILABLE")),
			invocations(state.runId),
		)
		makeRunnable(state.runId)

		worker.drain()

		assertEquals(2, gateway.writeCalls)
		assertEquals(1, gateway.reviewCalls)
		assertEquals(
			listOf(
				InvocationRow("WRITER", 0, 1, "FAILED", "PROVIDER_UNAVAILABLE"),
				InvocationRow("WRITER", 0, 2, "SUCCEEDED", null),
				InvocationRow("REVIEWER", 1, 1, "SUCCEEDED", null),
			),
			invocations(state.runId),
		)
		assertEquals(1, count("generation_workflow_steps", state.runId, "step_kind = 'WRITER'"))
		assertEquals(1, count("generation_artifacts", state.runId, "artifact_type = 'WRITER_OUTPUT'"))
		assertEquals(1, count("content_packs", state.runId))
	}

	@Test
	fun claimedArtifactWorkflowCreatesOneAttemptAndNestedModelObservation() {
		val state = reserve("observed-success")
		val observations = TestObservationRegistry.create()
		val worker = ArtifactWorkflowRunWorker(
			persistence = persistence,
			workflowService = workflow,
			modelGateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 0),
			workerId = "observed-worker",
			observationRegistry = observations,
		)

		assertEquals(true, worker.processOne())
		observations.assertThat().hasNumberOfObservationsWithNameEqualTo("plot.generation.attempt", 1)
		observations.assertThat().hasNumberOfObservationsWithNameEqualTo("plot.generation.model_call", 1)
		observations.assertThat().hasAnObservationWithAKeyValue("plot.generation_run_id", state.runId.toString())
		observations.assertThat().forAllObservationsWithNameEqualTo("plot.generation.model_call") {
			it.hasParentObservation()
		}
	}

	@Test
	fun retryCreatesAnIndependentAttemptObservationWithSafeOutcome() {
		val state = reserve("observed-retry")
		val observations = TestObservationRegistry.create()
		val worker = ArtifactWorkflowRunWorker(
			persistence = persistence,
			workflowService = workflow,
			modelGateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 1),
			workerId = "observed-retry-worker",
			observationRegistry = observations,
		)

		assertEquals(true, worker.processOne())
		makeRunnable(state.runId)
		assertEquals(true, worker.processOne())

		observations.assertThat().hasNumberOfObservationsWithNameEqualTo("plot.generation.attempt", 2)
		observations.assertThat().hasAnObservationWithAKeyValue("plot.outcome", "RETRY_SCHEDULED")
		observations.assertThat().hasAnObservationWithAKeyValue("plot.outcome", "SUCCEEDED")
		observations.assertThat().hasAnObservationWithAKeyValue("plot.error_code", "PROVIDER_UNAVAILABLE")
	}

	@Test
	fun emptyArtifactWorkflowPollDoesNotCreateAnObservation() {
		val observations = TestObservationRegistry.create()
		val worker = ArtifactWorkflowRunWorker(
			persistence = persistence,
			workflowService = workflow,
			modelGateway = RetryGateway(UUID.randomUUID(), transientWriterFailures = 0),
			workerId = "empty-observed-worker",
			observationRegistry = observations,
		)

		assertEquals(false, worker.processOne())
		observations.assertThat().doesNotHaveAnyObservation()
	}

	@Test
	fun exporterFailureDoesNotChangeArtifactWorkflowCheckpoint() {
		val state = reserve("observed-exporter-failure")
		val registry = ObservationRegistry.create()
		registry.observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
			override fun supportsContext(context: Observation.Context): Boolean = true

			override fun onStop(context: Observation.Context): Unit =
				throw IllegalStateException("telemetry endpoint unavailable")
		})
		val worker = ArtifactWorkflowRunWorker(
			persistence = persistence,
			workflowService = workflow,
			modelGateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 0),
			workerId = "exporter-failure-worker",
			observationRegistry = registry,
		)

		assertEquals(true, worker.processOne())
		assertEquals("REVIEWING", runStatus(state.runId))
		assertEquals(1, count("model_invocations", state.runId))
	}

	@Test
	fun thirdTransientFailureStopsWithoutAFourthProviderCall() {
		val state = reserve("bounded-retry")
		val gateway = RetryGateway(state.evidence.single().id, transientWriterFailures = Int.MAX_VALUE)
		val worker = ArtifactWorkflowRunWorker(persistence, workflow, gateway, workerId = "bounded-worker")

		repeat(3) { attempt ->
			assertEquals(true, worker.processOne())
			if (attempt < 2) makeRunnable(state.runId)
		}

		assertEquals(3, gateway.writeCalls)
		assertFalse(worker.processOne())
		assertEquals("FAILED", runStatus(state.runId))
		assertEquals(
			listOf(1, 2, 3),
			invocations(state.runId).map { it.attemptNo },
		)
		assertEquals(0, count("content_packs", state.runId))
	}

	@Test
	fun failedAttemptMetadataCountsTowardTokenAndTimingBudgets() {
		val state = reserve("failed-metadata", maxTotalTokens = 5)
		insertFailedInvocationWithMetadata(state.runId, totalTokens = 7, latencyMs = 12)
		val claim = assertNotNull(persistence.claimNext("budget-worker", Instant.now().minusSeconds(120)))

		assertEquals("TOKEN_BUDGET_EXHAUSTED", persistence.budgetFailureCode(claim))
		val timing = persistence.loadTiming(devContext.devWorkspaceId, state.runId)
		assertEquals(7, timing.model?.totalTokens)
		assertEquals(12, timing.model?.totalLatencyMs)
	}

	@Test
	fun retryTransitionPersistsReturnedMetadataForBudgetAndTiming() {
		val state = reserve("retry-metadata", maxTotalTokens = 5)
		val claim = assertNotNull(persistence.claimNext("metadata-worker", Instant.now().minusSeconds(120)))
		val invocation = persistence.beginInvocation(claim, com.plot.api.ai.provider.ModelRole.WRITER)
		persistence.scheduleInvocationRetry(
			claim = claim,
			lease = invocation,
			code = "PROVIDER_UNAVAILABLE",
			nextAttemptAt = Instant.now(),
			metadata = ModelCallMetadata(
				responseId = "response-1",
				actualModel = "scripted",
				finishReason = null,
				promptTokens = 3,
				completionTokens = 4,
				totalTokens = 7,
				latency = Duration.ofMillis(12),
				observationAttributes = mapOf("gateway" to "test"),
			),
		)

		val replacement = assertNotNull(
			persistence.claimNext("metadata-worker", Instant.now().minusSeconds(120)),
		)
		assertEquals("TOKEN_BUDGET_EXHAUSTED", persistence.budgetFailureCode(replacement))
		val timing = persistence.loadTiming(devContext.devWorkspaceId, state.runId)
		assertEquals(7, timing.model?.totalTokens)
		assertEquals(12, timing.model?.totalLatencyMs)
	}

	@Test
	fun staleRecoveryCreatesTheNextAttemptForTheSameLogicalStep() {
		val state = reserve("stale-retry")
		val firstClaim = assertNotNull(persistence.claimNext("first-worker", Instant.now().minusSeconds(120)))
		val firstAttempt = persistence.beginInvocation(firstClaim, ModelRole.WRITER)
		jdbcTemplate.update(
			"update generation_runs set heartbeat_at = now() - interval '10 minutes' where id = ?",
			state.runId,
		)

		assertEquals(1, persistence.recoverStaleClaims(Instant.now().minusSeconds(120)))
		val replacement = assertNotNull(
			persistence.claimNext("replacement-worker", Instant.now().minusSeconds(120)),
		)
		val secondAttempt = persistence.beginInvocation(replacement, ModelRole.WRITER)

		assertEquals(firstAttempt.stepId, secondAttempt.stepId)
		assertEquals(firstAttempt.logicalCallIndex, secondAttempt.logicalCallIndex)
		assertEquals(2, secondAttempt.attemptNo)
		assertEquals(
			listOf(
				InvocationRow("WRITER", 0, 1, "FAILED", "LEASE_LOST_OUTCOME_UNKNOWN"),
				InvocationRow("WRITER", 0, 2, "RUNNING", null),
			),
			invocations(state.runId),
		)
	}

	@Test
	fun outcomeUnknownAttemptConsumesAModelCallSlot() {
		val state = reserve("outcome-unknown-budget", maxModelCalls = 1)
		val claim = assertNotNull(persistence.claimNext("stale-worker", Instant.now().minusSeconds(120)))
		persistence.beginInvocation(claim, ModelRole.WRITER)
		jdbcTemplate.update(
			"update generation_runs set heartbeat_at = now() - interval '10 minutes' where id = ?",
			state.runId,
		)

		assertEquals(1, persistence.recoverStaleClaims(Instant.now().minusSeconds(120)))
		val replacement = assertNotNull(
			persistence.claimNext("replacement-worker", Instant.now().minusSeconds(120)),
		)

		assertEquals("MODEL_CALL_BUDGET_EXHAUSTED", persistence.budgetFailureCode(replacement))
	}

	@Test
	fun heartbeatFailureBeforeModelResponseDiscardsTheStaleResult() {
		val state = reserve("heartbeat-loss")
		val observations = TestObservationRegistry.create()
		val openedLease = AtomicReference<ArtifactWorkflowRunLease>()
		val leaseFactory = ArtifactWorkflowRunLeaseFactory { claim ->
			val lease = ArtifactWorkflowRunLease(claim, renewClaim = { _, _ -> false }, java.time.Clock.systemUTC())
			openedLease.set(lease)
			ArtifactWorkflowRunLeaseHandle(lease) {}
		}
		val gateway = RetryGateway(
			evidenceId = state.evidence.single().id,
			transientWriterFailures = 0,
			beforeWriteResult = { openedLease.get().renew() },
		)
		val worker = ArtifactWorkflowRunWorker(
			persistence = persistence,
			workflowService = workflow,
			modelGateway = gateway,
			workerId = "heartbeat-loss-worker",
			leaseFactory = leaseFactory,
			observationRegistry = observations,
		)

		assertEquals(true, worker.processOne())

		assertEquals(1, gateway.writeCalls)
		assertNull(worker.lastFailure)
		assertEquals("WRITING", runStatus(state.runId))
		assertEquals(
			listOf(InvocationRow("WRITER", 0, 1, "RUNNING", null)),
			invocations(state.runId),
		)
		assertEquals(0, count("generation_artifacts", state.runId, "artifact_type = 'WRITER_OUTPUT'"))
		assertEquals(0, count("content_packs", state.runId))
		observations.assertThat().hasAnObservationWithAKeyValue("plot.outcome", "LEASE_LOST")
	}

	private fun reserve(
		key: String,
		maxTotalTokens: Int = 80_000,
		maxModelCalls: Int = 12,
	): ArtifactWorkflowState {
		val runId = UUID.randomUUID()
		val blockId = insertWritingBlock(key)
		val state = workflow.start(
			runId,
			listOf(
				EvidenceSnapshot(
					id = UUID.randomUUID(),
					artifactWorkflowRunId = runId,
					writingBlockId = blockId,
					orderIndex = 0,
					sourceProvider = SourceProvider.GITHUB,
					sourceKind = "pull_request",
					sourceLabel = "PR $key",
					snapshotTitle = "PR $key",
					snapshotBody = "Shipped evidence",
					snapshotExcerpt = "Shipped evidence",
					originalUrl = "https://github.test/acme/repo/pull/$key",
					sourceCreatedAt = null,
					sourceUpdatedAt = null,
					contentHash = "hash-$key",
					capturedAt = Instant.now(),
				),
			),
			null,
		)
		return persistence.createRun(
			ArtifactWorkflowRunReservation(
				workspaceId = devContext.devWorkspaceId,
				createdByUserId = devContext.devUserId,
				sourceScopeId = null,
				idempotencyKey = "physical-$key-${UUID.randomUUID()}",
				requestFingerprint = "fingerprint-$key",
				state = state,
				provider = "OPENAI",
				modelName = "scripted",
				budgetJson = """
					{"maxModelCalls":$maxModelCalls,"maxTotalTokens":$maxTotalTokens,"maxRunDurationMillis":300000}
				""".trimIndent(),
			),
		)
	}

	private fun insertWritingBlock(key: String): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update(
			"""
			insert into writing_blocks (id, workspace_id, source_origin, source_kind, title, body, url,
			 content_hash, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'github', 'pull_request', ?, 'evidence', ?, ?, now(), 'ACTIVE', ?, now(), now())
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			"PR $key",
			"https://github.test/acme/repo/pull/$key",
			"block-$key",
			devContext.devUserId,
		)
	}

	private fun insertFailedInvocationWithMetadata(runId: UUID, totalTokens: Int, latencyMs: Int) {
		val stepId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into generation_workflow_steps (
			 id, workspace_id, generation_run_id, step_kind, sequence_no, semantic_attempt,
			 status, failure_code, started_at, finished_at, created_at
			) values (?, ?, ?, 'WRITER', 0, 0, 'FAILED', 'PROVIDER_UNAVAILABLE', now(), now(), now())
			""".trimIndent(),
			stepId,
			devContext.devWorkspaceId,
			runId,
		)
		jdbcTemplate.update(
			"""
			insert into model_invocations (
			 id, workspace_id, generation_run_id, workflow_step_id, role, logical_call_index, attempt_no,
			 status, provider, model_name, total_token_count, latency_ms, failure_code,
			 started_at, finished_at, created_at
			) values (?, ?, ?, ?, 'WRITER', 0, 1, 'FAILED', 'OPENAI', 'scripted', ?, ?,
			 'PROVIDER_UNAVAILABLE', now(), now(), now())
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			runId,
			stepId,
			totalTokens,
			latencyMs,
		)
	}

	private fun makeRunnable(runId: UUID) {
		jdbcTemplate.update("update generation_runs set next_attempt_at = now() where id = ?", runId)
	}

	private fun runStatus(runId: UUID): String = jdbcTemplate.queryForObject(
		"select status from generation_runs where id = ?",
		String::class.java,
		runId,
	)!!

	private fun invocations(runId: UUID): List<InvocationRow> = jdbcTemplate.query(
		"""
		select role, logical_call_index, attempt_no, status, failure_code
		from model_invocations
		where generation_run_id = ?
		order by logical_call_index, attempt_no
		""".trimIndent(),
		{ rs, _ ->
			InvocationRow(
				role = rs.getString("role"),
				logicalCallIndex = rs.getInt("logical_call_index"),
				attemptNo = rs.getInt("attempt_no"),
				status = rs.getString("status"),
				failureCode = rs.getString("failure_code"),
			)
		},
		runId,
	)

	private fun count(table: String, runId: UUID, predicate: String = "true"): Int =
		jdbcTemplate.queryForObject(
			"select count(*) from $table where generation_run_id = ? and $predicate",
			Int::class.java,
			runId,
		)!!
}

private data class InvocationRow(
	val role: String,
	val logicalCallIndex: Int,
	val attemptNo: Int,
	val status: String,
	val failureCode: String?,
)

private class RetryGateway(
	private val evidenceId: UUID,
	private var transientWriterFailures: Int,
	private val beforeWriteResult: () -> Unit = {},
) : ArtifactWorkflowModelGateway {
	var writeCalls: Int = 0
		private set
	var reviewCalls: Int = 0
		private set

	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> {
		writeCalls++
		if (transientWriterFailures > 0) {
			transientWriterFailures--
			throw ArtifactWorkflowModelException(
				ModelFailureCode.PROVIDER_UNAVAILABLE,
				"temporary provider failure",
			)
		}
		beforeWriteResult()
		return result(WriterOutput(listOf(WriterSentence("Search shipped."))))
	}

	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> {
		reviewCalls++
		return result(
			ReviewerOutput(
				listOf(
					SentenceReview(
						sentenceId = request.sentences.single().id,
						verdict = ReviewVerdict.SUPPORTED,
						evidenceIds = listOf(evidenceId),
					),
				),
			),
		)
	}

	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> =
		error("rewrite is not expected")

	private fun <T : Any> result(value: T): ModelCallResult<T> = ModelCallResult(
		value,
		ModelCallMetadata(
			responseId = "scripted",
			actualModel = "scripted",
			finishReason = "stop",
			promptTokens = 1,
			completionTokens = 1,
			totalTokens = 2,
			latency = Duration.ofMillis(1),
			observationAttributes = emptyMap(),
		),
	)
}
