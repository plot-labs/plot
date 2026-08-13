package com.plot.api.github

import com.plot.api.common.ApiException
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.artifact.run.ArtifactRunStatus
import com.plot.api.artifact.run.ArtifactRunWorkflowState
import com.plot.api.artifact.workflow.ArtifactWorkflowRunStatus
import com.plot.api.artifact.workflow.ArtifactWorkflowState
import com.plot.api.routine.AgentRunOrigin
import com.plot.api.routine.AgentRunRecord
import com.plot.api.routine.AgentRunStatus
import io.micrometer.observation.tck.TestObservationRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.task.TaskRejectedException
import org.springframework.http.HttpStatus

class GitHubReleaseDraftOrchestratorTest {
	@Test
	fun firstTagEndsNeedsRangeWithoutStartingArtifactWorkflow() {
		val fixture = fixture(GitHubReleaseRangeResult.NeedsRange("first-head"))

		fixture.orchestrator.process(fixture.request, fixture.lease)

		assertEquals(0, fixture.generation.created.size)
		assertEquals(0, fixture.persistence.finished.size)
		assertNull(fixture.persistence.linkedArtifactWorkflow)
	}

	@Test
	fun claimedReleaseCreatesOneAttemptObservationWithOpaqueCorrelations() {
		val observations = TestObservationRegistry.create()
		val fixture = fixture(resolvedRange(), observationRegistry = observations)
		fixture.persistence.claims += fixture.request

		assertEquals(1, fixture.worker.drain())
		observations.assertThat().hasNumberOfObservationsWithNameEqualTo("plot.github.release.attempt", 1)
		observations.assertThat().hasAnObservationWithAKeyValue("plot.release_request_id", fixture.request.id.toString())
		observations.assertThat().hasAnObservationWithAKeyValue("plot.webhook_delivery_id", fixture.request.initialDeliveryId.toString())
		observations.assertThat().hasAnObservationWithAKeyValue("plot.outcome", "SUCCEEDED")
	}

	@Test
	fun emptyReleasePollDoesNotCreateAnObservation() {
		val observations = TestObservationRegistry.create()
		val fixture = fixture(resolvedRange(), observationRegistry = observations)

		assertEquals(0, fixture.worker.drain())
		observations.assertThat().doesNotHaveAnyObservation()
	}

	@Test
	fun secondAncestorTagStartsExactlyOneArtifactWorkflowAndLinksItBeforeClaimRelease() {
		val fixture = fixture(resolvedRange())

		fixture.orchestrator.process(fixture.request, fixture.lease)

		assertEquals(1, fixture.generation.created.size)
		val creation = fixture.generation.created.single()
		assertEquals(fixture.context.workspaceId, creation.principal.workspaceId)
		assertEquals(fixture.context.createdByUserId, creation.principal.userId)
		assertEquals(fixture.context.sourceScopeId, creation.sourceScopeId)
		assertEquals(
			"github-release:${fixture.context.workspaceId}:${fixture.context.sourceScopeId}:${fixture.context.repositoryId}:v2:attempt:0",
			creation.idempotencyKey,
		)
		assertEquals(
			LinkedArtifactWorkflow(fixture.request.id, 2, EVIDENCE_ID, GENERATION_ID),
			fixture.persistence.linkedArtifactWorkflow,
		)
		assertEquals(
			BoundEvidence(fixture.request.id, 2, GitHubReleaseEvidence(EVIDENCE_ID, listOf(WRITING_BLOCK_ID))),
			fixture.persistence.boundEvidence.single(),
		)
		assertTrue(fixture.persistence.claimWasHeldWhenArtifactWorkflowLinked)
	}

	@Test
	fun emptyEvidenceEndsNoActivityWithoutStartingArtifactWorkflow() {
		val fixture = fixture(resolvedRange(), evidence = GitHubReleaseEvidence(EVIDENCE_ID, emptyList()))

		fixture.orchestrator.process(fixture.request, fixture.lease)

		assertEquals(0, fixture.generation.created.size)
		assertEquals(
			FinishedRequest(fixture.request.id, 2, GitHubReleaseDraftStatus.NO_ACTIVITY, null),
			fixture.persistence.finished.single(),
		)
	}

	@Test
	fun identicalRangeIsPersistedBeforeNoActivityFinishes() {
		val fixture = fixture(
			GitHubReleaseRangeResult.NoActivity(
				baseSha = "same",
				headSha = "same",
				boundaryReason = "PREVIOUS_RELEASE_HEAD",
			),
		)

		fixture.orchestrator.process(fixture.request, fixture.lease)

		assertEquals(Triple("same", "same", "PREVIOUS_RELEASE_HEAD"), fixture.persistence.savedRange)
		assertEquals(
			FinishedRequest(fixture.request.id, 2, GitHubReleaseDraftStatus.NO_ACTIVITY, null),
			fixture.persistence.finished.single(),
		)
		assertEquals(0, fixture.evidence.collectCount)
		assertEquals(0, fixture.generation.created.size)
	}

	@Test
	fun movedObservedTagFailsSafelyBeforeEvidenceOrArtifactWorkflow() {
		val fixture = fixture(GitHubReleaseRangeResult.NeedsRange("unused"))
		fixture.range.failure = GitHubReleasePermanentException("GITHUB_TAG_MOVED")
		fixture.persistence.claims += fixture.request

		fixture.worker.drain()

		assertEquals(
			FinishedRequest(fixture.request.id, 1, GitHubReleaseDraftStatus.FAILED, "GITHUB_TAG_MOVED"),
			fixture.persistence.finished.single(),
		)
		assertEquals(0, fixture.evidence.collectCount)
		assertEquals(0, fixture.generation.created.size)
	}

	@Test
	fun retryAfterArtifactWorkflowCreationReusesBoundObservationAndItsFrozenArtifactWorkflow() {
		val fixture = fixture(resolvedRange())
		fixture.persistence.failNextArtifactWorkflowLink = true
		fixture.persistence.claims += fixture.request

		fixture.worker.drain()

		assertEquals(1, fixture.generation.uniqueRunCount())
		assertEquals(1, fixture.evidence.collectCount)
		assertEquals(EVIDENCE_ID, fixture.persistence.boundEvidence.single().evidence.observationId)
		fixture.evidence.next = GitHubReleaseEvidence(UUID.randomUUID(), listOf(UUID.randomUUID()))
		val retry = fixture.request.copy(
			baseSha = "base",
			headSha = "head",
			boundaryReason = "PREVIOUS_RELEASE_HEAD",
			status = GitHubReleaseDraftStatus.RESOLVING,
			attemptCount = 2,
			transitionVersion = 5,
			observationId = EVIDENCE_ID,
		)
		fixture.persistence.claims += retry

		fixture.worker.drain()

		assertEquals(1, fixture.evidence.collectCount)
		assertEquals(1, fixture.generation.uniqueRunCount())
		assertEquals(EVIDENCE_ID, fixture.persistence.linkedArtifactWorkflow?.observationId)
		assertEquals(
			fixture.generation.created.first().writingBlockIds,
			fixture.persistence.findBoundEvidence(fixture.request.id)?.writingBlockIds,
		)
	}

	@Test
	fun nextArtifactWorkflowAttemptUsesANewAttemptScopedArtifactWorkflowIdentity() {
		val fixture = fixture(resolvedRange())
		fixture.orchestrator.process(fixture.request, fixture.lease)
		val failedRun = checkNotNull(fixture.persistence.linkedArtifactWorkflow).artifactWorkflowRunId
		fixture.generation.loaded[failedRun] = generationState(ArtifactWorkflowRunStatus.FAILED, failedRun)
		fixture.persistence.generating += fixture.request.copy(
			status = GitHubReleaseDraftStatus.GENERATING,
			transitionVersion = 4,
			artifactWorkflowRunId = failedRun,
			agentRunId = failedRun,
			observationId = EVIDENCE_ID,
		)
		fixture.orchestrator.reconcileGenerating(10)
		assertEquals(GitHubReleaseDraftStatus.FAILED, fixture.persistence.finished.single().status)
		fixture.persistence.finished.clear()

		val retry = fixture.request.copy(
			status = GitHubReleaseDraftStatus.RESOLVING,
			attemptCount = 2,
			transitionVersion = 6,
			artifactWorkflowRunId = failedRun,
			agentRunId = failedRun,
			observationId = EVIDENCE_ID,
			generationAttempt = 1,
		)
		fixture.lease.reset(6)
		fixture.orchestrator.process(retry, fixture.lease)
		val successfulRun = checkNotNull(fixture.persistence.linkedArtifactWorkflow).artifactWorkflowRunId
		fixture.generation.loaded[successfulRun] = generationState(ArtifactWorkflowRunStatus.READY, successfulRun)
		fixture.persistence.generating.clear()
		fixture.persistence.generating += retry.copy(
			status = GitHubReleaseDraftStatus.GENERATING,
			transitionVersion = 7,
			artifactWorkflowRunId = successfulRun,
			agentRunId = successfulRun,
		)

		fixture.orchestrator.reconcileGenerating(10)

		assertTrue(failedRun != successfulRun)
		assertEquals(2, fixture.generation.uniqueRunCount())
		assertEquals(GitHubReleaseDraftStatus.READY, fixture.persistence.finished.single().status)
	}

	@Test
	fun duplicateSignalsForTheSameTagReuseOneArtifactWorkflowRun() {
		val fixture = fixture(resolvedRange())

		fixture.orchestrator.process(fixture.request, fixture.lease)
		fixture.lease.reset(1)
		fixture.orchestrator.process(fixture.request.copy(transitionVersion = 1), fixture.lease)

		assertEquals(2, fixture.generation.created.size)
		assertSame(fixture.generation.created[0].state, fixture.generation.created[1].state)
		assertEquals(1, fixture.generation.uniqueRunCount())
	}

	@Test
	fun reconcilesReviewableArtifactWorkflowStatesToReady() {
		listOf(ArtifactWorkflowRunStatus.READY, ArtifactWorkflowRunStatus.NEEDS_REVIEW).forEach { status ->
			val fixture = fixture(resolvedRange())
			fixture.persistence.generating += fixture.request.copy(
				status = GitHubReleaseDraftStatus.GENERATING,
				transitionVersion = 5,
				artifactWorkflowRunId = GENERATION_ID,
				agentRunId = GENERATION_ID,
			)
			fixture.generation.loaded[GENERATION_ID] = generationState(status)

			fixture.orchestrator.reconcileGenerating(10)

			assertEquals(
				FinishedRequest(fixture.request.id, 6, GitHubReleaseDraftStatus.READY, null),
				fixture.persistence.finished.single(),
			)
		}
	}

	@Test
	fun reconcilesFailedArtifactWorkflowToSafeReleaseFailure() {
		val fixture = fixture(resolvedRange())
		fixture.persistence.generating += fixture.request.copy(
			status = GitHubReleaseDraftStatus.GENERATING,
			transitionVersion = 5,
			artifactWorkflowRunId = GENERATION_ID,
			agentRunId = GENERATION_ID,
		)
		fixture.generation.loaded[GENERATION_ID] = generationState(ArtifactWorkflowRunStatus.FAILED)

		fixture.orchestrator.reconcileGenerating(10)

		assertEquals(
			FinishedRequest(fixture.request.id, 5, GitHubReleaseDraftStatus.FAILED, "ARTIFACT_WORKFLOW_FAILED"),
			fixture.persistence.finished.single(),
		)
	}

	@Test
	fun poisonArtifactWorkflowRowRecordsSafeDiagnosticAndDoesNotBlockLaterRows() {
		val fixture = fixture(resolvedRange())
		val poisonRunId = UUID.randomUUID()
		val readyRunId = UUID.randomUUID()
		fixture.persistence.generating += listOf(
			fixture.request.copy(artifactWorkflowRunId = poisonRunId, agentRunId = poisonRunId, transitionVersion = 4),
			fixture.request.copy(id = UUID.randomUUID(), artifactWorkflowRunId = readyRunId, agentRunId = readyRunId, transitionVersion = 7),
		)
		fixture.generation.loadFailures[poisonRunId] = IllegalStateException("unsafe diagnostic")
		fixture.generation.loaded[readyRunId] = ArtifactWorkflowState(
			runId = readyRunId,
			evidence = emptyList(),
			instruction = "release",
			status = ArtifactWorkflowRunStatus.READY,
		)

		fixture.orchestrator.reconcileGenerating(10)

		assertEquals(
			ReleaseDiagnostic(fixture.request.id, 4, "ARTIFACT_WORKFLOW_RECONCILE_FAILED"),
			fixture.persistence.diagnostics.single(),
		)
		assertEquals(GitHubReleaseDraftStatus.READY, fixture.persistence.finished.single().status)
	}

	@Test
	fun workerSchedulesRetryOnTheSameRequestAfterRetryableGitHubFailure() {
		val fixture = fixture(resolvedRange())
		fixture.range.failure = ApiException(
			HttpStatus.TOO_MANY_REQUESTS,
			"GITHUB_RATE_LIMITED",
			"unsafe provider detail",
		)
		fixture.persistence.claims += fixture.request

		fixture.worker.drain()

		assertEquals(
			ScheduledRetry(
				fixture.request.id,
				fixture.request.transitionVersion,
				NOW.plusSeconds(10),
				"GITHUB_RATE_LIMITED",
			),
			fixture.persistence.scheduledRetries.single(),
		)
		assertEquals(0, fixture.generation.created.size)
	}

	@Test
	fun transientArtifactWorkflowStartFailureRetriesTheSameRequestAtThePostRangeVersion() {
		val fixture = fixture(resolvedRange())
		fixture.generation.createFailure = TaskRejectedException("executor unavailable")
		fixture.persistence.claims += fixture.request

		fixture.worker.drain()

		assertEquals(
			ScheduledRetry(
				fixture.request.id,
				2,
				NOW.plusSeconds(10),
				"AGENT_ADMISSION_START_TRANSIENT",
			),
			fixture.persistence.scheduledRetries.single(),
		)
		assertNull(fixture.persistence.linkedArtifactWorkflow)
	}

	@Test
	fun lostLeaseAbortsBeforeArtifactWorkflowAndDoesNotMutateTheRequestAsItsFormerOwner() {
		val fixture = fixture(resolvedRange())
		fixture.lease.loseAfterCheckpoint = 2
		fixture.persistence.claims += fixture.request

		fixture.worker.drain()

		assertEquals(0, fixture.generation.created.size)
		assertEquals(0, fixture.persistence.finished.size)
		assertEquals(0, fixture.persistence.scheduledRetries.size)
	}

	@Test
	fun drainProcessesOneRequestSoReconciliationGetsItsOwnTurn() {
		val fixture = fixture(GitHubReleaseRangeResult.NeedsRange("head"))
		repeat(2) { fixture.persistence.claims += fixture.request.copy(id = UUID.randomUUID()) }
		fixture.persistence.generating += fixture.request.copy(
			status = GitHubReleaseDraftStatus.GENERATING,
			artifactWorkflowRunId = GENERATION_ID,
			agentRunId = GENERATION_ID,
		)
		fixture.generation.loaded[GENERATION_ID] = generationState(ArtifactWorkflowRunStatus.READY)

		val processed = fixture.worker.drain()
		fixture.worker.reconcile()

		assertEquals(1, processed)
		assertEquals(1, fixture.persistence.claims.size)
		assertEquals(GitHubReleaseDraftStatus.READY, fixture.persistence.finished.single().status)
	}

	@Test
	fun workerStopsRetryingAfterMaxAttempts() {
		val fixture = fixture(
			resolvedRange(),
			properties = GitHubProperties(releaseAutomationEnabled = true, releaseWorkerMaxAttempts = 2),
		)
		fixture.range.failure = ApiException(
			HttpStatus.BAD_GATEWAY,
			"GITHUB_PROVIDER_UNAVAILABLE",
			"provider body must not be stored",
		)
		fixture.persistence.claims += fixture.request.copy(attemptCount = 2)

		fixture.worker.drain()

		assertEquals(0, fixture.persistence.scheduledRetries.size)
		assertEquals(
			FinishedRequest(fixture.request.id, 1, GitHubReleaseDraftStatus.FAILED, "GITHUB_PROVIDER_UNAVAILABLE"),
			fixture.persistence.finished.single(),
		)
	}

	@Test
	fun permanentPermissionLossFailsWithoutPersistingProviderMessage() {
		val fixture = fixture(resolvedRange())
		fixture.range.failure = ApiException(
			HttpStatus.BAD_GATEWAY,
			"GITHUB_ACCESS_DENIED",
			"secret provider body",
		)
		fixture.persistence.claims += fixture.request

		fixture.worker.drain()

		assertEquals(0, fixture.persistence.scheduledRetries.size)
		assertEquals(
			FinishedRequest(fixture.request.id, 1, GitHubReleaseDraftStatus.FAILED, "GITHUB_ACCESS_DENIED"),
			fixture.persistence.finished.single(),
		)
	}

	private fun fixture(
		rangeResult: GitHubReleaseRangeResult,
		evidence: GitHubReleaseEvidence = GitHubReleaseEvidence(EVIDENCE_ID, listOf(WRITING_BLOCK_ID)),
		properties: GitHubProperties = GitHubProperties(releaseAutomationEnabled = true),
		observationRegistry: TestObservationRegistry = TestObservationRegistry.create(),
	): Fixture {
		val persistence = FakeReleasePersistence()
		val context = context()
		val delivery = delivery()
		val request = request()
		persistence.deliveries[delivery.id] = delivery
		val scope = FakeScopeResolver(context)
		val range = FakeRangeResolver(rangeResult)
		val evidenceService = FakeEvidenceService(evidence)
		val admission = FakeReleaseAgentAdmission(persistence)
		val executionProbe = FakeReleaseExecutionProbe(admission)
		val lease = FakeReleaseLease()
		val orchestrator = GitHubReleaseDraftOrchestrator(
			persistence,
			scope,
			range,
			evidenceService,
			admission,
			executionProbe,
		)
		val worker = GitHubReleaseDraftWorker(
			persistence,
			orchestrator,
			properties,
			FakeReleaseLeaseFactory(lease),
			Clock.fixed(NOW, ZoneOffset.UTC),
			workerId = "release-test-worker",
			observationRegistry = observationRegistry,
		)
		return Fixture(
			persistence,
			context,
			request,
			range,
			evidenceService,
			admission,
			lease,
			orchestrator,
			worker,
		)
	}

	private fun resolvedRange() = GitHubReleaseRangeResult.Resolved(
		GitHubReleaseRange(
			baseSha = "base",
			headSha = "head",
			boundaryReason = "PREVIOUS_RELEASE_HEAD",
			comparison = GitHubCompareResult(
				status = "ahead",
				aheadBy = 1,
				commits = listOf(
					GitHubCommit(
						sha = "head",
						message = "Release change",
						url = "https://github.com/acme/plot/commit/head",
						author = "Plot",
						committedAt = NOW,
					),
				),
				files = emptyList(),
				filesTruncated = false,
			),
		),
	)

	private fun context() = GitHubReleaseSourceContext(
		workspaceId = WORKSPACE_ID,
		createdByUserId = USER_ID,
		connectionId = UUID.randomUUID(),
		bindingId = UUID.randomUUID(),
		sourceNamespaceId = UUID.randomUUID(),
		sourceScopeId = SCOPE_ID,
		installationId = 77,
		repositoryId = 44,
		owner = "acme",
		repository = "plot",
		defaultBranch = "main",
	)

	private fun request() = GitHubReleaseDraftRequest(
		id = REQUEST_ID,
		workspaceId = WORKSPACE_ID,
		sourceScopeId = SCOPE_ID,
		initialDeliveryId = DELIVERY_ID,
		tagName = "v2",
		baseSha = null,
		headSha = null,
		boundaryReason = null,
		status = GitHubReleaseDraftStatus.RESOLVING,
		attemptCount = 1,
		transitionVersion = 1,
		artifactWorkflowRunId = null,
		observationId = null,
		errorCode = null,
	)

	private fun delivery() = GitHubWebhookDelivery(
		id = DELIVERY_ID,
		externalDeliveryId = "delivery-1",
		eventType = "create",
		eventAction = null,
		installationId = 77,
		repositoryId = 44,
		ref = "refs/tags/v2",
		beforeSha = null,
		afterSha = "head",
		tagName = "v2",
		refCreated = true,
		refDeleted = false,
		forced = false,
		payloadHash = "a".repeat(64),
		disposition = GitHubWebhookDisposition.QUEUED,
		errorCode = null,
		receivedAt = NOW,
		processedAt = NOW,
	)

	private fun generationState(
		status: ArtifactWorkflowRunStatus,
		runId: UUID = GENERATION_ID,
	) = ArtifactWorkflowState(
		runId = runId,
		evidence = emptyList(),
		instruction = "release",
		status = status,
	)

	private data class Fixture(
		val persistence: FakeReleasePersistence,
		val context: GitHubReleaseSourceContext,
		val request: GitHubReleaseDraftRequest,
		val range: FakeRangeResolver,
		val evidence: FakeEvidenceService,
		val generation: FakeReleaseAgentAdmission,
		val lease: FakeReleaseLease,
		val orchestrator: GitHubReleaseDraftOrchestrator,
		val worker: GitHubReleaseDraftWorker,
	)

	private class FakeScopeResolver(
		private val context: GitHubReleaseSourceContext,
	) : GitHubReleaseScopeResolver {
		override fun resolve(installationId: Long, repositoryId: Long): GitHubReleaseSourceContext? = context
	}

	private class FakeRangeResolver(
		private val result: GitHubReleaseRangeResult,
	) : GitHubReleaseRangeResolver {
		var failure: RuntimeException? = null

		override fun resolve(
			context: GitHubReleaseSourceContext,
			request: GitHubReleaseDraftRequest,
		): GitHubReleaseRangeResult {
			failure?.let { throw it }
			return result
		}
	}

	private class FakeEvidenceService(
		evidence: GitHubReleaseEvidence,
	) : GitHubReleaseEvidenceService {
		var next: GitHubReleaseEvidence = evidence
		var collectCount = 0

		override fun collect(
			principal: WorkspacePrincipal,
			context: GitHubReleaseSourceContext,
			request: GitHubReleaseDraftRequest,
			range: GitHubReleaseRange,
		): GitHubReleaseEvidence {
			collectCount += 1
			return next
		}
	}

	private class FakeReleaseAgentAdmission(
		private val persistence: GitHubReleasePersistence,
	) : GitHubReleaseAgentAdmission {
		val created = mutableListOf<ArtifactWorkflowCreation>()
		val loaded = mutableMapOf<UUID, ArtifactWorkflowState>()
		val loadFailures = mutableMapOf<UUID, RuntimeException>()
		var createFailure: RuntimeException? = null

		override fun bindAndAdmit(
			request: GitHubReleaseDraftRequest,
			transitionVersion: Long,
			principal: WorkspacePrincipal,
			evidence: GitHubReleaseEvidence,
			instruction: String,
			idempotencyKey: String,
		): AgentRunRecord {
			createFailure?.let { throw it }
			if (request.observationId == null) {
				persistence.bindEvidence(request.id, transitionVersion, evidence)
			}
			val existing = created.firstOrNull { it.idempotencyKey == idempotencyKey }
			if (existing != null) {
				created += existing
				return agentRun(existing.state, principal, idempotencyKey, instruction).also {
					persistence.linkAgentRun(request.id, transitionVersion, evidence.observationId, it.id)
				}
			}
			val state = ArtifactWorkflowState(
				runId = if (created.isEmpty()) GENERATION_ID else SECOND_GENERATION_ID,
				evidence = emptyList(),
				instruction = "release",
				status = ArtifactWorkflowRunStatus.QUEUED,
			)
			created += ArtifactWorkflowCreation(
				principal,
				request.sourceScopeId,
				evidence.writingBlockIds,
				instruction,
				idempotencyKey,
				state,
			)
			return agentRun(state, principal, idempotencyKey, instruction).also {
				persistence.linkAgentRun(request.id, transitionVersion, evidence.observationId, it.id)
			}
		}

		fun uniqueRunCount(): Int = created.map { it.state.runId }.distinct().size

		private fun agentRun(
			state: ArtifactWorkflowState,
			principal: WorkspacePrincipal,
			idempotencyKey: String,
			instruction: String,
		): AgentRunRecord = AgentRunRecord(
				id = state.runId,
				workspaceId = principal.workspaceId,
				routineExecutionId = null,
				workSessionId = CHAT_ID,
				routineId = null,
				origin = AgentRunOrigin.ROUTINE,
				idempotencyKey = idempotencyKey,
				requestFingerprint = idempotencyKey,
				createdByUserId = principal.userId,
				instructionSnapshot = instruction,
				promptVersion = "test",
				toolPolicyVersion = "test",
				budgetSnapshotJson = "{}",
				status = when (state.status) {
					ArtifactWorkflowRunStatus.FAILED -> AgentRunStatus.FAILED
					else -> AgentRunStatus.RUNNING
				},
				currentStep = 0,
				attemptCount = 0,
				maxAttempts = 3,
				modelCallCount = 0,
				toolCallCount = 0,
				nextAttemptAt = null,
				failureCode = state.failureCode,
				claimedBy = null,
				claimedAt = null,
				transitionVersion = 0,
				startedAt = null,
				finishedAt = null,
				createdAt = NOW,
				updatedAt = NOW,
			)
	}

	private class FakeReleaseExecutionProbe(
		private val admission: FakeReleaseAgentAdmission,
	) : GitHubReleaseExecutionProbe {
		override fun findArtifact(workspaceId: UUID, agentRunId: UUID): ArtifactRunWorkflowState? {
			admission.loadFailures[agentRunId]?.let { throw it }
			val state = admission.loaded[agentRunId] ?: return null
			return ArtifactRunWorkflowState(
				artifactRunId = agentRunId,
				agentRunId = agentRunId,
				workflowRunId = state.runId,
				status = when (state.status) {
					ArtifactWorkflowRunStatus.QUEUED -> ArtifactRunStatus.QUEUED
					ArtifactWorkflowRunStatus.WRITING -> ArtifactRunStatus.WRITING
					ArtifactWorkflowRunStatus.REVIEWING -> ArtifactRunStatus.REVIEWING
					ArtifactWorkflowRunStatus.REWRITING -> ArtifactRunStatus.REWRITING
					ArtifactWorkflowRunStatus.READY -> ArtifactRunStatus.READY
					ArtifactWorkflowRunStatus.NEEDS_REVIEW -> ArtifactRunStatus.NEEDS_REVIEW
					ArtifactWorkflowRunStatus.FAILED -> ArtifactRunStatus.FAILED
				},
				errorCode = state.failureCode,
				materialized = state.status in setOf(ArtifactWorkflowRunStatus.READY, ArtifactWorkflowRunStatus.NEEDS_REVIEW),
			)
		}

		override fun findAgentStatus(workspaceId: UUID, agentRunId: UUID): AgentRunStatus? =
			admission.loaded[agentRunId]?.let { state ->
				if (state.status == ArtifactWorkflowRunStatus.FAILED) AgentRunStatus.FAILED else AgentRunStatus.RUNNING
			}
	}

	private class FakeReleasePersistence : GitHubReleasePersistence {
		val deliveries = mutableMapOf<UUID, GitHubWebhookDelivery>()
		val claims = ArrayDeque<GitHubReleaseDraftRequest>()
		val finished = mutableListOf<FinishedRequest>()
		val scheduledRetries = mutableListOf<ScheduledRetry>()
		val boundEvidence = mutableListOf<BoundEvidence>()
		val diagnostics = mutableListOf<ReleaseDiagnostic>()
		val generating = mutableListOf<GitHubReleaseDraftRequest>()
		var linkedArtifactWorkflow: LinkedArtifactWorkflow? = null
		var claimWasHeldWhenArtifactWorkflowLinked = false
		var failNextArtifactWorkflowLink = false
		private var rangeSaved = false
		var savedRange: Triple<String, String, String>? = null

		override fun findDelivery(id: UUID): GitHubWebhookDelivery? = deliveries[id]

		override fun claimNext(
			workerId: String,
			now: Instant,
			leaseTimeout: Duration,
		): GitHubReleaseDraftRequest? = claims.removeFirstOrNull()

		override fun saveResolvedRange(
			requestId: UUID,
			transitionVersion: Long,
			baseSha: String,
			headSha: String,
			boundaryReason: String,
		) {
			assertEquals(1, transitionVersion)
			rangeSaved = true
			savedRange = Triple(baseSha, headSha, boundaryReason)
		}

		override fun linkAgentRun(
			requestId: UUID,
			transitionVersion: Long,
			observationId: UUID,
			agentRunId: UUID,
		) {
			if (failNextArtifactWorkflowLink) {
				failNextArtifactWorkflowLink = false
				throw TaskRejectedException("simulated crash after agent admission")
			}
			linkedArtifactWorkflow = LinkedArtifactWorkflow(requestId, transitionVersion, observationId, agentRunId)
			claimWasHeldWhenArtifactWorkflowLinked = rangeSaved
		}

		override fun linkAgentArtifact(
			requestId: UUID,
			transitionVersion: Long,
			agentRunId: UUID,
			artifactWorkflowRunId: UUID,
		) {
			linkedArtifactWorkflow = LinkedArtifactWorkflow(
				requestId,
				transitionVersion,
				boundEvidence.lastOrNull { it.requestId == requestId }?.evidence?.observationId ?: EVIDENCE_ID,
				artifactWorkflowRunId,
			)
		}

		override fun bindEvidence(
			requestId: UUID,
			transitionVersion: Long,
			evidence: GitHubReleaseEvidence,
		) {
			boundEvidence += BoundEvidence(requestId, transitionVersion, evidence)
		}

		override fun findBoundEvidence(requestId: UUID): GitHubReleaseEvidence? =
			boundEvidence.lastOrNull { it.requestId == requestId }?.evidence

		override fun renewClaim(
			requestId: UUID,
			transitionVersion: Long,
			workerId: String,
			now: Instant,
		): Boolean = true

		override fun recordReconcileDiagnostic(
			requestId: UUID,
			transitionVersion: Long,
			errorCode: String,
		) {
			diagnostics += ReleaseDiagnostic(requestId, transitionVersion, errorCode)
		}

		override fun finish(
			requestId: UUID,
			transitionVersion: Long,
			status: GitHubReleaseDraftStatus,
			errorCode: String?,
		) {
			finished += FinishedRequest(requestId, transitionVersion, status, errorCode)
		}

		override fun scheduleRetry(
			requestId: UUID,
			transitionVersion: Long,
			nextAttemptAt: Instant,
			errorCode: String,
		) {
			scheduledRetries += ScheduledRetry(requestId, transitionVersion, nextAttemptAt, errorCode)
		}

		override fun findGenerating(limit: Int): List<GitHubReleaseDraftRequest> = generating.take(limit)
		override fun recoverStaleClaims(now: Instant, leaseTimeout: Duration): Int = 0
		override fun fenceSourceScope(
			workspaceId: UUID,
			sourceScopeId: UUID,
			now: Instant,
			errorCode: String,
		): Int = 0
		override fun insertDelivery(delivery: GitHubWebhookDelivery): GitHubWebhookDelivery = error("not used")
		override fun findDelivery(externalDeliveryId: String): GitHubWebhookDelivery? = error("not used")
		override fun markDelivery(id: UUID, disposition: GitHubWebhookDisposition, errorCode: String?) = error("not used")
		override fun enqueueRelease(
			workspaceId: UUID,
			sourceScopeId: UUID,
			deliveryId: UUID,
			tagName: String,
			observedHeadSha: String?,
		): GitHubReleaseDraftRequest = error("not used")
		override fun releaseScopeExists(sourceScopeId: UUID, workspaceId: UUID): Boolean = error("not used")
		override fun findLatestActivity(sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseActivityRecord? =
			error("not used")
		override fun findActivity(
			requestId: UUID,
			sourceScopeId: UUID,
			workspaceId: UUID,
		): GitHubReleaseActivityRecord? = error("not used")
		override fun findLatest(sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseDraftRequest? = error("not used")
		override fun findPreviousBoundaries(
			workspaceId: UUID,
			sourceScopeId: UUID,
			excludingRequestId: UUID,
		): List<GitHubReleaseDraftRequest> = error("not used")
		override fun saveHeadAndFinishNeedsRange(
			requestId: UUID,
			transitionVersion: Long,
			headSha: String,
		) = Unit
		override fun retry(requestId: UUID, workspaceId: UUID, transitionVersion: Long) = error("not used")
	}

	private class FakeReleaseLease : GitHubReleaseLease {
		override val workerId: String = "release-test-worker"
		override var transitionVersion: Long = 1
			private set
		var loseAfterCheckpoint: Int? = null
		private var checkpoints = 0

		override fun checkpoint() {
			checkpoints += 1
			if (loseAfterCheckpoint == checkpoints) throw GitHubReleaseLeaseLostException()
		}

		override fun advanceTransition() {
			transitionVersion += 1
		}

		fun reset(version: Long) {
			transitionVersion = version
		}
	}

	private class FakeReleaseLeaseFactory(
		private val lease: FakeReleaseLease,
	) : GitHubReleaseLeaseFactory {
		override fun open(request: GitHubReleaseDraftRequest, workerId: String): GitHubReleaseLeaseHandle {
			lease.reset(request.transitionVersion)
			return GitHubReleaseLeaseHandle(lease) {}
		}
	}

	private data class ArtifactWorkflowCreation(
		val principal: WorkspacePrincipal,
		val sourceScopeId: UUID,
		val writingBlockIds: List<UUID>,
		val instruction: String,
		val idempotencyKey: String,
		val state: ArtifactWorkflowState,
	)

	private data class LinkedArtifactWorkflow(
		val requestId: UUID,
		val transitionVersion: Long,
		val observationId: UUID,
		val artifactWorkflowRunId: UUID,
	)

	private data class FinishedRequest(
		val requestId: UUID,
		val transitionVersion: Long,
		val status: GitHubReleaseDraftStatus,
		val errorCode: String?,
	)

	private data class ScheduledRetry(
		val requestId: UUID,
		val transitionVersion: Long,
		val nextAttemptAt: Instant,
		val errorCode: String,
	)

	private data class BoundEvidence(
		val requestId: UUID,
		val transitionVersion: Long,
		val evidence: GitHubReleaseEvidence,
	)

	private data class ReleaseDiagnostic(
		val requestId: UUID,
		val transitionVersion: Long,
		val errorCode: String,
	)

	private companion object {
		val NOW: Instant = Instant.parse("2026-07-30T00:00:00Z")
		val WORKSPACE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
		val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
		val SCOPE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
		val REQUEST_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")
		val DELIVERY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000005")
		val EVIDENCE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000006")
		val WRITING_BLOCK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000007")
		val GENERATION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000008")
		val SECOND_GENERATION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000009")
		val CHAT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
	}
}
