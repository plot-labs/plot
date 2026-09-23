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
import com.plot.api.billing.PolarApiException
import com.plot.api.billing.PolarCreditOverview
import com.plot.api.billing.PolarCreditProvider
import com.plot.api.billing.PolarCreditService
import com.plot.api.billing.PolarCustomer
import com.plot.api.billing.PolarEventResult
import com.plot.api.dev.DevContext
import com.plot.api.artifact.workflow.ArtifactWorkflowAdmissionPersistence
import com.plot.api.artifact.workflow.ArtifactWorkflowExecutionPersistence
import com.plot.api.artifact.workflow.ArtifactWorkflowQueryPersistence
import com.plot.api.artifact.workflow.ArtifactWorkflowRecoveryPersistence
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
import java.math.BigDecimal
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class, ArtifactWorkflowPhysicalAttemptIntegrationTest.Config::class)
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.polar.credits-enabled=true",
	"plot.polar.access-token=test-token",
	"plot.polar.ai-meter-id=test-meter",
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArtifactWorkflowPhysicalAttemptIntegrationTest {
	@Autowired private lateinit var executionPersistence: ArtifactWorkflowExecutionPersistence
	@Autowired private lateinit var queryPersistence: ArtifactWorkflowQueryPersistence
	@Autowired private lateinit var admissionPersistence: ArtifactWorkflowAdmissionPersistence
	@Autowired private lateinit var recoveryPersistence: ArtifactWorkflowRecoveryPersistence
	@Autowired private lateinit var workflow: ArtifactWorkflowService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var creditService: PolarCreditService
	@Autowired private lateinit var polarCredits: ArtifactPolarCreditProvider

	@BeforeEach
	fun isolateArtifactWorkflowQueue() {
		polarCredits.reset()
		jdbcTemplate.update(
			"""update model_invocations
				set status = 'FAILED', billing_status = case when billing_status = 'PENDING' then 'USAGE_UNKNOWN' else billing_status end,
				    failure_code = coalesce(failure_code, 'TEST_ISOLATION'),
				    finished_at = greatest(coalesce(finished_at, now()), coalesce(started_at, now()))
				where status = 'RUNNING'""",
		)
		jdbcTemplate.update(
			"""
			update generation_runs
			set status = 'FAILED', error_code = 'TEST_ISOLATION',
			    claimed_by = null, claimed_at = null, heartbeat_at = null,
			    finished_at = coalesce(finished_at, greatest(now(), coalesce(started_at, created_at))), updated_at = now()
			where status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			""".trimIndent(),
		)
	}

	@Test
	fun writerAndReviewerEachSettleOneActualUsageEvent() {
		val state = reserve("billed-writer-reviewer")
		val gateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 0)
		val worker = billedWorker(gateway, "billed-writer-reviewer")

		worker.drain()

		assertEquals(2, polarCredits.events.size)
		assertEquals(2, count("model_invocations", state.runId, "billing_status = 'SETTLED'"))
		assertEquals(listOf("REVIEWER", "WRITER"), invocationRoles(state.runId).sorted())
		assertEquals("READY", runStatus(state.runId))
	}

	@Test
	fun exhaustedCreditsPreventTheProviderCall() {
		val state = reserve("no-credit")
		val gateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 0)
		polarCredits.balance = 0

		assertEquals(true, billedWorker(gateway, "no-credit").processOne())

		assertEquals(0, gateway.writeCalls)
		assertEquals("FAILED", runStatus(state.runId))
		assertEquals("AI_CREDITS_EXHAUSTED", runFailure(state.runId))
	}

	@Test
	fun pendingSettlementRecoversWithoutRepeatingProviderWork() {
		val state = reserve("pending-settlement")
		val gateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 0)
		val worker = billedWorker(gateway, "pending-settlement")
		polarCredits.failIngest = true

		assertEquals(true, worker.processOne())
		assertEquals(1, gateway.writeCalls)
		assertEquals(1, count("model_invocations", state.runId, "billing_status = 'PENDING'"))
		polarCredits.failIngest = false
		makeRunnable(state.runId)

		assertEquals(true, worker.processOne())
		assertEquals(1, gateway.writeCalls)
		assertEquals(1, polarCredits.events.size)
		assertEquals("FAILED", runStatus(state.runId))
		assertEquals("AI_SETTLEMENT_RECOVERED", runFailure(state.runId))
	}

	@Test
	fun unknownUsageFailsOnlyTheCurrentWorkflow() {
		val first = reserve("usage-unknown")
		val missingCost = RetryGateway(
			first.evidence.single().id,
			transientWriterFailures = 0,
			reportedCostUsd = null,
		)

		assertEquals(true, billedWorker(missingCost, "usage-unknown").processOne())
		assertEquals("AI_USAGE_UNKNOWN", runFailure(first.runId))
		assertEquals(0, count("model_invocations", first.runId, "billing_status = 'PENDING'"))

		val second = reserve("usage-known-after-unknown")
		val valid = RetryGateway(second.evidence.single().id, transientWriterFailures = 0)
		assertEquals(true, billedWorker(valid, "usage-known-after-unknown").processOne())
		assertEquals(1, valid.writeCalls)
	}

	@Test
	fun malformedOutputWithKnownUsageIsChargedOnceAndFailsTheWorkflow() {
		val state = reserve("malformed-billed")
		val gateway = RetryGateway(
			state.evidence.single().id,
			transientWriterFailures = 0,
			malformedWriter = true,
		)

		assertEquals(true, billedWorker(gateway, "malformed-billed").processOne())

		assertEquals(1, gateway.writeCalls)
		assertEquals(1, polarCredits.events.size)
		assertEquals(1, count("model_invocations", state.runId, "billing_status = 'SETTLED'"))
		assertEquals("MALFORMED_OUTPUT", runFailure(state.runId))
	}

	@Test
	fun transientFailureCreatesANewAttemptForTheSameLogicalStep() {
		val state = reserve("transient-then-success")
		val gateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 1)
		val worker = ArtifactWorkflowRunWorker(executionPersistence, queryPersistence, workflow, gateway, workerId = "retry-worker")

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
			executionPersistence = executionPersistence,
			queryPersistence = queryPersistence,
			workflowService = workflow,
			modelGateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 0),
			workerId = "observed-worker",
			observationRegistry = observations,
		)

		assertEquals(true, worker.processOne())
		observations.assertThat().hasNumberOfObservationsWithNameEqualTo("plot.artifact_workflow.attempt", 1)
		observations.assertThat().hasNumberOfObservationsWithNameEqualTo("plot.artifact_workflow.model_call", 1)
		observations.assertThat().hasAnObservationWithAKeyValue("plot.artifact_workflow_run_id", state.runId.toString())
		observations.assertThat().forAllObservationsWithNameEqualTo("plot.artifact_workflow.model_call") {
			it.hasParentObservation()
		}
	}

	@Test
	fun retryCreatesAnIndependentAttemptObservationWithSafeOutcome() {
		val state = reserve("observed-retry")
		val observations = TestObservationRegistry.create()
		val worker = ArtifactWorkflowRunWorker(
			executionPersistence = executionPersistence,
			queryPersistence = queryPersistence,
			workflowService = workflow,
			modelGateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 1),
			workerId = "observed-retry-worker",
			observationRegistry = observations,
		)

		assertEquals(true, worker.processOne())
		makeRunnable(state.runId)
		assertEquals(true, worker.processOne())

		observations.assertThat().hasNumberOfObservationsWithNameEqualTo("plot.artifact_workflow.attempt", 2)
		observations.assertThat().hasAnObservationWithAKeyValue("plot.outcome", "RETRY_SCHEDULED")
		observations.assertThat().hasAnObservationWithAKeyValue("plot.outcome", "SUCCEEDED")
		observations.assertThat().hasAnObservationWithAKeyValue("plot.error_code", "PROVIDER_UNAVAILABLE")
	}

	@Test
	fun emptyArtifactWorkflowPollDoesNotCreateAnObservation() {
		val observations = TestObservationRegistry.create()
		val worker = ArtifactWorkflowRunWorker(
			executionPersistence = executionPersistence,
			queryPersistence = queryPersistence,
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
			executionPersistence = executionPersistence,
			queryPersistence = queryPersistence,
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
		val worker = ArtifactWorkflowRunWorker(executionPersistence, queryPersistence, workflow, gateway, workerId = "bounded-worker")

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
		val claim = assertNotNull(executionPersistence.claimNext("budget-worker", Instant.now().minusSeconds(120)))

		assertEquals("TOKEN_BUDGET_EXHAUSTED", executionPersistence.budgetFailureCode(claim))
		val timing = queryPersistence.loadTiming(devContext.devWorkspaceId, state.runId)
		assertEquals(7, timing.model?.totalTokens)
		assertEquals(12, timing.model?.totalLatencyMs)
	}

	@Test
	fun retryTransitionPersistsReturnedMetadataForBudgetAndTiming() {
		val state = reserve("retry-metadata", maxTotalTokens = 5)
		val claim = assertNotNull(executionPersistence.claimNext("metadata-worker", Instant.now().minusSeconds(120)))
		val invocation = executionPersistence.beginInvocation(claim, com.plot.api.ai.provider.ModelRole.WRITER)
		executionPersistence.scheduleInvocationRetry(
			claim = claim,
			lease = invocation,
			code = "PROVIDER_UNAVAILABLE",
			nextAttemptAt = Instant.now().minusSeconds(1),
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
			executionPersistence.claimNext("metadata-worker", Instant.now().minusSeconds(120)),
		)
		assertEquals("TOKEN_BUDGET_EXHAUSTED", executionPersistence.budgetFailureCode(replacement))
		val timing = queryPersistence.loadTiming(devContext.devWorkspaceId, state.runId)
		assertEquals(7, timing.model?.totalTokens)
		assertEquals(12, timing.model?.totalLatencyMs)
	}

	@Test
	fun staleRecoveryMarksUnknownUsageAndDoesNotRepeatTheProviderCall() {
		val state = reserve("stale-usage-unknown")
		val firstClaim = assertNotNull(executionPersistence.claimNext("first-worker", Instant.now().minusSeconds(120)))
		val firstAttempt = executionPersistence.beginInvocation(firstClaim, ModelRole.WRITER, enforceSettlementGate = true)
		jdbcTemplate.update(
			"update generation_runs set heartbeat_at = now() - interval '10 minutes' where id = ?",
			state.runId,
		)

		assertEquals(1, recoveryPersistence.recoverStaleClaims(Instant.now().minusSeconds(120)))
		assertEquals(
			"RUNNING:USAGE_UNKNOWN:AI_USAGE_UNKNOWN",
			jdbcTemplate.queryForObject(
				"select status || ':' || billing_status || ':' || failure_code from model_invocations where id = ?",
				String::class.java,
				firstAttempt.id,
			),
		)
		val gateway = RetryGateway(state.evidence.single().id, transientWriterFailures = 0)

		assertEquals(true, billedWorker(gateway, "recovery-worker").processOne())

		assertEquals(0, gateway.writeCalls)
		assertEquals("FAILED", runStatus(state.runId))
		assertEquals("AI_USAGE_UNKNOWN", runFailure(state.runId))
		assertEquals(
			listOf(
				InvocationRow("WRITER", 0, 1, "FAILED", "AI_USAGE_UNKNOWN"),
			),
			invocations(state.runId),
		)

		val nextRun = reserve("stale-usage-unknown-does-not-block-workspace")
		val nextGateway = RetryGateway(nextRun.evidence.single().id, transientWriterFailures = 0)
		assertEquals(true, billedWorker(nextGateway, "after-unknown-worker").processOne())
		assertEquals(1, nextGateway.writeCalls)
	}

	@Test
	fun outcomeUnknownAttemptConsumesAModelCallSlot() {
		val state = reserve("outcome-unknown-budget", maxModelCalls = 1)
		val claim = assertNotNull(executionPersistence.claimNext("stale-worker", Instant.now().minusSeconds(120)))
		executionPersistence.beginInvocation(claim, ModelRole.WRITER)
		jdbcTemplate.update(
			"update generation_runs set heartbeat_at = now() - interval '10 minutes' where id = ?",
			state.runId,
		)

		assertEquals(1, recoveryPersistence.recoverStaleClaims(Instant.now().minusSeconds(120)))
		val replacement = assertNotNull(
			executionPersistence.claimNext("replacement-worker", Instant.now().minusSeconds(120)),
		)

		assertEquals("MODEL_CALL_BUDGET_EXHAUSTED", executionPersistence.budgetFailureCode(replacement))
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
			executionPersistence = executionPersistence,
			queryPersistence = queryPersistence,
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
		return admissionPersistence.createRun(
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

	private fun billedWorker(gateway: ArtifactWorkflowModelGateway, workerId: String) = ArtifactWorkflowRunWorker(
		executionPersistence = executionPersistence,
		queryPersistence = queryPersistence,
		workflowService = workflow,
		modelGateway = gateway,
		workerId = workerId,
		creditService = creditService,
	)

	private fun runStatus(runId: UUID): String = jdbcTemplate.queryForObject(
		"select status from generation_runs where id = ?",
		String::class.java,
		runId,
	)!!

	private fun runFailure(runId: UUID): String = jdbcTemplate.queryForObject(
		"select error_code from generation_runs where id = ?",
		String::class.java,
		runId,
	)!!

	private fun invocationRoles(runId: UUID): List<String> = jdbcTemplate.queryForList(
		"select role from model_invocations where generation_run_id = ? and billing_status = 'SETTLED'",
		String::class.java,
		runId,
	).filterNotNull()

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

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun polarCreditProvider() = ArtifactPolarCreditProvider()
	}
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
	private val reportedCostUsd: BigDecimal? = BigDecimal("0.000001"),
	private val malformedWriter: Boolean = false,
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
		if (malformedWriter) {
			throw ArtifactWorkflowModelException(
				ModelFailureCode.MALFORMED_OUTPUT,
				"malformed provider output",
				metadata = metadata(),
			)
		}
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
		metadata(),
	)

	private fun metadata() = ModelCallMetadata(
			responseId = "scripted",
			actualModel = "scripted",
			finishReason = "stop",
			promptTokens = 1,
			completionTokens = 1,
			totalTokens = 2,
			latency = Duration.ofMillis(1),
			observationAttributes = emptyMap(),
			gateway = "openrouter",
			requestedModel = "scripted",
			cacheReadTokens = 0,
			cacheWriteTokens = 0,
			reasoningTokens = 0,
			reportedCostUsd = reportedCostUsd,
	)
}

class ArtifactPolarCreditProvider : PolarCreditProvider {
	var balance = 10_000L
	var failIngest = false
	val events = mutableListOf<ArtifactPolarEvent>()

	override fun ensureCustomer(workspaceId: UUID, ownerEmail: String, workspaceName: String, existingCustomerId: String?) =
		PolarCustomer("cus-$workspaceId", "plot-workspace:$workspaceId")

	override fun readCreditBalance(workspaceId: UUID): Long = balance

	override fun readCreditOverview(workspaceId: UUID) = PolarCreditOverview(
		balance = balance,
		creditedUnits = balance,
		consumedUnits = 0,
		usageEvents = emptyList(),
	)

	override fun ingestCredits(
		workspaceId: UUID,
		eventId: String,
		credits: Long,
		metadata: Map<String, Any>,
	): PolarEventResult {
		if (failIngest) throw PolarApiException("POLAR_UNAVAILABLE", "Polar unavailable", retryable = true)
		if (events.none { it.eventId == eventId }) {
			events += ArtifactPolarEvent(workspaceId, eventId, credits)
			balance -= credits
			return PolarEventResult(1, 0)
		}
		return PolarEventResult(0, 1)
	}

	fun reset() {
		balance = 10_000
		failIngest = false
		events.clear()
	}
}

data class ArtifactPolarEvent(val workspaceId: UUID, val eventId: String, val credits: Long)
