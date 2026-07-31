package com.plot.api.github

import com.plot.api.common.ApiException
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.generation.GenerationRunStatus
import com.plot.api.generation.GenerationWorkflowState
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
	fun firstTagEndsNeedsRangeWithoutStartingGeneration() {
		val fixture = fixture(GitHubReleaseRangeResult.NeedsRange("first-head"))

		fixture.orchestrator.process(fixture.request, fixture.lease)

		assertEquals(0, fixture.generation.created.size)
		assertEquals(0, fixture.persistence.finished.size)
		assertNull(fixture.persistence.linkedGeneration)
	}

	@Test
	fun secondAncestorTagStartsExactlyOneGenerationAndLinksItBeforeClaimRelease() {
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
			LinkedGeneration(fixture.request.id, 3, EVIDENCE_ID, GENERATION_ID),
			fixture.persistence.linkedGeneration,
		)
		assertEquals(
			BoundEvidence(fixture.request.id, 2, GitHubReleaseEvidence(EVIDENCE_ID, listOf(WRITING_BLOCK_ID))),
			fixture.persistence.boundEvidence.single(),
		)
		assertTrue(fixture.persistence.claimWasHeldWhenGenerationLinked)
	}

	@Test
	fun emptyEvidenceEndsNoActivityWithoutStartingGeneration() {
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
	fun movedObservedTagFailsSafelyBeforeEvidenceOrGeneration() {
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
	fun retryAfterGenerationCreationReusesBoundObservationAndItsFrozenGeneration() {
		val fixture = fixture(resolvedRange())
		fixture.persistence.failNextGenerationLink = true
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
		assertEquals(EVIDENCE_ID, fixture.persistence.linkedGeneration?.observationId)
		assertEquals(
			fixture.generation.created.first().writingBlockIds,
			fixture.persistence.findBoundEvidence(fixture.request.id)?.writingBlockIds,
		)
	}

	@Test
	fun nextGenerationAttemptUsesANewAttemptScopedGenerationIdentity() {
		val fixture = fixture(resolvedRange())
		fixture.orchestrator.process(fixture.request, fixture.lease)
		val failedRun = checkNotNull(fixture.persistence.linkedGeneration).generationRunId
		fixture.generation.loaded[failedRun] = generationState(GenerationRunStatus.FAILED, failedRun)
		fixture.persistence.generating += fixture.request.copy(
			status = GitHubReleaseDraftStatus.GENERATING,
			transitionVersion = 4,
			generationRunId = failedRun,
			observationId = EVIDENCE_ID,
		)
		fixture.orchestrator.reconcileGenerating(10)
		assertEquals(GitHubReleaseDraftStatus.FAILED, fixture.persistence.finished.single().status)
		fixture.persistence.finished.clear()

		val retry = fixture.request.copy(
			status = GitHubReleaseDraftStatus.RESOLVING,
			attemptCount = 2,
			transitionVersion = 6,
			generationRunId = failedRun,
			observationId = EVIDENCE_ID,
			generationAttempt = 1,
		)
		fixture.lease.reset(6)
		fixture.orchestrator.process(retry, fixture.lease)
		val successfulRun = checkNotNull(fixture.persistence.linkedGeneration).generationRunId
		fixture.generation.loaded[successfulRun] = generationState(GenerationRunStatus.READY, successfulRun)
		fixture.persistence.generating.clear()
		fixture.persistence.generating += retry.copy(
			status = GitHubReleaseDraftStatus.GENERATING,
			transitionVersion = 7,
			generationRunId = successfulRun,
		)

		fixture.orchestrator.reconcileGenerating(10)

		assertTrue(failedRun != successfulRun)
		assertEquals(2, fixture.generation.uniqueRunCount())
		assertEquals(GitHubReleaseDraftStatus.READY, fixture.persistence.finished.single().status)
	}

	@Test
	fun duplicateSignalsForTheSameTagReuseOneGenerationRun() {
		val fixture = fixture(resolvedRange())

		fixture.orchestrator.process(fixture.request, fixture.lease)
		fixture.lease.reset(1)
		fixture.orchestrator.process(fixture.request.copy(transitionVersion = 1), fixture.lease)

		assertEquals(2, fixture.generation.created.size)
		assertSame(fixture.generation.created[0].state, fixture.generation.created[1].state)
		assertEquals(1, fixture.generation.uniqueRunCount())
	}

	@Test
	fun reconcilesReviewableGenerationStatesToReady() {
		listOf(GenerationRunStatus.READY, GenerationRunStatus.NEEDS_REVIEW).forEach { status ->
			val fixture = fixture(resolvedRange())
			fixture.persistence.generating += fixture.request.copy(
				status = GitHubReleaseDraftStatus.GENERATING,
				transitionVersion = 5,
				generationRunId = GENERATION_ID,
			)
			fixture.generation.loaded[GENERATION_ID] = generationState(status)

			fixture.orchestrator.reconcileGenerating(10)

			assertEquals(
				FinishedRequest(fixture.request.id, 5, GitHubReleaseDraftStatus.READY, null),
				fixture.persistence.finished.single(),
			)
		}
	}

	@Test
	fun reconcilesFailedGenerationToSafeReleaseFailure() {
		val fixture = fixture(resolvedRange())
		fixture.persistence.generating += fixture.request.copy(
			status = GitHubReleaseDraftStatus.GENERATING,
			transitionVersion = 5,
			generationRunId = GENERATION_ID,
		)
		fixture.generation.loaded[GENERATION_ID] = generationState(GenerationRunStatus.FAILED)

		fixture.orchestrator.reconcileGenerating(10)

		assertEquals(
			FinishedRequest(fixture.request.id, 5, GitHubReleaseDraftStatus.FAILED, "GENERATION_FAILED"),
			fixture.persistence.finished.single(),
		)
	}

	@Test
	fun poisonGenerationRowRecordsSafeDiagnosticAndDoesNotBlockLaterRows() {
		val fixture = fixture(resolvedRange())
		val poisonRunId = UUID.randomUUID()
		val readyRunId = UUID.randomUUID()
		fixture.persistence.generating += listOf(
			fixture.request.copy(generationRunId = poisonRunId, transitionVersion = 4),
			fixture.request.copy(id = UUID.randomUUID(), generationRunId = readyRunId, transitionVersion = 7),
		)
		fixture.generation.loadFailures[poisonRunId] = IllegalStateException("unsafe diagnostic")
		fixture.generation.loaded[readyRunId] = GenerationWorkflowState(
			runId = readyRunId,
			evidence = emptyList(),
			instruction = "release",
			status = GenerationRunStatus.READY,
		)

		fixture.orchestrator.reconcileGenerating(10)

		assertEquals(
			ReleaseDiagnostic(fixture.request.id, 4, "GENERATION_RECONCILE_FAILED"),
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
	fun transientGenerationStartFailureRetriesTheSameRequestAtThePostRangeVersion() {
		val fixture = fixture(resolvedRange())
		fixture.generation.createFailure = TaskRejectedException("executor unavailable")
		fixture.persistence.claims += fixture.request

		fixture.worker.drain()

		assertEquals(
			ScheduledRetry(
				fixture.request.id,
				2,
				NOW.plusSeconds(10),
				"GENERATION_START_TRANSIENT",
			),
			fixture.persistence.scheduledRetries.single(),
		)
		assertNull(fixture.persistence.linkedGeneration)
	}

	@Test
	fun lostLeaseAbortsBeforeGenerationAndDoesNotMutateTheRequestAsItsFormerOwner() {
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
			generationRunId = GENERATION_ID,
		)
		fixture.generation.loaded[GENERATION_ID] = generationState(GenerationRunStatus.READY)

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
	): Fixture {
		val persistence = FakeReleasePersistence()
		val context = context()
		val delivery = delivery()
		val request = request()
		persistence.deliveries[delivery.id] = delivery
		val scope = FakeScopeResolver(context)
		val range = FakeRangeResolver(rangeResult)
		val evidenceService = FakeEvidenceService(evidence)
		val generation = FakeReleaseGenerationGateway()
		val binder = FakeReleaseEvidenceGenerationBinder(persistence, generation)
		val lease = FakeReleaseLease()
		val orchestrator = GitHubReleaseDraftOrchestrator(
			persistence,
			scope,
			range,
			evidenceService,
			generation,
			binder,
		)
		val worker = GitHubReleaseDraftWorker(
			persistence,
			orchestrator,
			properties,
			FakeReleaseLeaseFactory(lease),
			Clock.fixed(NOW, ZoneOffset.UTC),
			workerId = "release-test-worker",
		)
		return Fixture(
			persistence,
			context,
			request,
			range,
			evidenceService,
			generation,
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
		generationRunId = null,
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
		status: GenerationRunStatus,
		runId: UUID = GENERATION_ID,
	) = GenerationWorkflowState(
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
		val generation: FakeReleaseGenerationGateway,
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

	private class FakeReleaseGenerationGateway : GitHubReleaseGenerationGateway {
		val created = mutableListOf<GenerationCreation>()
		val loaded = mutableMapOf<UUID, GenerationWorkflowState>()
		val loadFailures = mutableMapOf<UUID, RuntimeException>()
		var createFailure: RuntimeException? = null

		override fun create(
			principal: WorkspacePrincipal,
			sourceScopeId: UUID,
			writingBlockIds: List<UUID>,
			instruction: String,
			idempotencyKey: String,
		): GenerationWorkflowState {
			createFailure?.let { throw it }
			val existing = created.firstOrNull { it.idempotencyKey == idempotencyKey }
			if (existing != null) {
				created += existing
				return existing.state
			}
			val state = GenerationWorkflowState(
				runId = if (created.isEmpty()) GENERATION_ID else SECOND_GENERATION_ID,
				evidence = emptyList(),
				instruction = "release",
				status = GenerationRunStatus.QUEUED,
			)
			created += GenerationCreation(
				principal,
				sourceScopeId,
				writingBlockIds,
				instruction,
				idempotencyKey,
				state,
			)
			return state
		}

		override fun load(workspaceId: UUID, runId: UUID): GenerationWorkflowState =
			loadFailures[runId]?.let { throw it } ?: loaded.getValue(runId)

		fun uniqueRunCount(): Int = created.map { it.state.runId }.distinct().size
	}

	private class FakeReleaseEvidenceGenerationBinder(
		private val persistence: GitHubReleasePersistence,
		private val generation: GitHubReleaseGenerationGateway,
	) : GitHubReleaseEvidenceGenerationBinder {
		override fun bindAndCreate(
			request: GitHubReleaseDraftRequest,
			transitionVersion: Long,
			principal: WorkspacePrincipal,
			evidence: GitHubReleaseEvidence,
			instruction: String,
			idempotencyKey: String,
		): GenerationWorkflowState {
			persistence.bindEvidence(request.id, transitionVersion, evidence)
			return generation.create(
				principal,
				request.sourceScopeId,
				evidence.writingBlockIds,
				instruction,
				idempotencyKey,
			)
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
		var linkedGeneration: LinkedGeneration? = null
		var claimWasHeldWhenGenerationLinked = false
		var failNextGenerationLink = false
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

		override fun linkGeneration(
			requestId: UUID,
			transitionVersion: Long,
			observationId: UUID,
			generationRunId: UUID,
		) {
			if (failNextGenerationLink) {
				failNextGenerationLink = false
				throw TaskRejectedException("simulated crash after generation")
			}
			linkedGeneration = LinkedGeneration(requestId, transitionVersion, observationId, generationRunId)
			claimWasHeldWhenGenerationLinked = rangeSaved
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

	private data class GenerationCreation(
		val principal: WorkspacePrincipal,
		val sourceScopeId: UUID,
		val writingBlockIds: List<UUID>,
		val instruction: String,
		val idempotencyKey: String,
		val state: GenerationWorkflowState,
	)

	private data class LinkedGeneration(
		val requestId: UUID,
		val transitionVersion: Long,
		val observationId: UUID,
		val generationRunId: UUID,
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
	}
}
