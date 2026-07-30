package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.dev.DevContext
import com.plot.api.generation.GenerationPersistence
import com.plot.api.generation.GenerationRunDispatcher
import com.plot.api.generation.GenerationRunStatus
import com.plot.api.source.ImportedWritingBlock
import com.plot.api.writingblock.WritingBlockImportService
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Import(TestcontainersConfiguration::class, GitHubReleaseDraftRecoveryIntegrationTest.DispatchConfig::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class GitHubReleaseDraftRecoveryIntegrationTest {
	@Autowired private lateinit var persistence: GitHubReleasePersistence
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var generationPersistence: GenerationPersistence
	@Autowired private lateinit var evidenceGenerationBinder: GitHubReleaseEvidenceGenerationBinder
	@Autowired private lateinit var writingBlockImportService: WritingBlockImportService
	@Autowired private lateinit var objectMapper: ObjectMapper
	@Autowired private lateinit var retryService: GitHubReleaseRetryService
	@Autowired private lateinit var retryDispatchProbe: CommitVisibleGenerationRetryDispatcher
	@Autowired private lateinit var transactionManager: PlatformTransactionManager

	@BeforeEach
	fun resetRetryDispatchProbe() {
		jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set status = 'FAILED', error_code = 'TEST_ISOLATION',
			    claimed_by = null, claimed_at = null, heartbeat_at = null,
			    finished_at = coalesce(finished_at, now()), updated_at = now()
			where workspace_id = ? and status in ('QUEUED', 'RESOLVING', 'GENERATING')
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
		retryDispatchProbe.reset()
	}

	@Test
	fun staleClaimIsRecoveredOnTheSameRequestAndBecomesRunnableAgain() {
		val requestId = insertClaimedRequest()
		val now = Instant.parse("2026-07-30T00:10:00Z")

		assertEquals(1, persistence.recoverStaleClaims(now, Duration.ofMinutes(2)))
		val recovered = persistence.claimNext("replacement-worker", now, Duration.ofMinutes(2))

		assertEquals(requestId, recovered?.id)
		assertEquals(2, recovered?.attemptCount)
		assertEquals(3, recovered?.transitionVersion)
		assertEquals(1, countRequests(requestId))
	}

	@Test
	fun delayedRetryKeepsTheSameRequestAndCannotRunBeforeNextAttempt() {
		val requestId = insertClaimedRequest()
		val retryAt = Instant.parse("2026-07-30T00:15:00Z")

		persistence.scheduleRetry(
			requestId = requestId,
			transitionVersion = 1,
			nextAttemptAt = retryAt,
			errorCode = "GITHUB_RATE_LIMITED",
		)

		assertEquals(null, persistence.claimNext("early-worker", retryAt.minusSeconds(1), Duration.ofMinutes(2)))
		assertEquals(requestId, persistence.claimNext("ready-worker", retryAt, Duration.ofMinutes(2))?.id)
		assertEquals(1, countRequests(requestId))
	}

	@Test
	fun staleClaimAfterRangeResolutionReturnsTheSameRequestToTheQueue() {
		val requestId = insertClaimedRequest()
		val staleAt = Instant.parse("2026-07-30T00:00:00Z")
		val now = Instant.parse("2026-07-30T00:10:00Z")
		jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set status = 'GENERATING', base_sha = 'base', head_sha = 'head',
				boundary_reason = 'PREVIOUS_RELEASE_HEAD', heartbeat_at = ?, transition_version = 2
			where id = ?
			""".trimIndent(),
			Timestamp.from(staleAt),
			requestId,
		)

		assertEquals(1, persistence.recoverStaleClaims(now, Duration.ofMinutes(2)))
		val recovered = persistence.claimNext("replacement-worker", now, Duration.ofMinutes(2))

		assertEquals(requestId, recovered?.id)
		assertEquals("base", recovered?.baseSha)
		assertEquals("head", recovered?.headSha)
		assertEquals(4, recovered?.transitionVersion)
		assertEquals(1, countRequests(requestId))
	}

	@Test
	fun leaseRenewalIsOwnershipAndVersionCheckedAcrossRecoveryAndReclaim() {
		val requestId = insertClaimedRequest()
		val heartbeatAt = Instant.parse("2026-07-30T00:01:00Z")

		assertEquals(true, persistence.renewClaim(requestId, 1, "dead-worker", heartbeatAt))
		assertEquals(0, persistence.recoverStaleClaims(heartbeatAt.plusSeconds(60), Duration.ofMinutes(2)))
		assertEquals(1, persistence.recoverStaleClaims(heartbeatAt.plusSeconds(180), Duration.ofMinutes(2)))
		val replacement = persistence.claimNext(
			"replacement-worker",
			heartbeatAt.plusSeconds(180),
			Duration.ofMinutes(2),
		)

		assertEquals(requestId, replacement?.id)
		assertEquals(false, persistence.renewClaim(requestId, 1, "dead-worker", heartbeatAt.plusSeconds(181)))
		assertEquals(true, persistence.renewClaim(
			requestId,
			checkNotNull(replacement).transitionVersion,
			"replacement-worker",
			heartbeatAt.plusSeconds(181),
		))
	}

	@Test
	fun recoveredBoundEvidenceLinkTransitionsToGeneratingAndIsNotRunnable() {
		val requestId = insertClaimedRequest()
		val observationId = insertObservationForRequest(requestId)
		val generationRunId = insertFailedGenerationForRequest(requestId)
		jdbcTemplate.update(
			"update github_release_draft_requests set observation_id = ? where id = ?",
			observationId,
			requestId,
		)

		persistence.linkGeneration(requestId, 1, observationId, generationRunId)

		assertEquals(
			"GENERATING",
			jdbcTemplate.queryForObject(
				"select status from github_release_draft_requests where id = ?",
				String::class.java,
				requestId,
			),
		)
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"""
				select count(*)
				from github_release_draft_requests
				where id = ? and status in ('QUEUED', 'RESOLVING')
				""".trimIndent(),
				Int::class.java,
				requestId,
			),
		)
	}

	@Test
	fun manualRetryDispatchesFreshFrozenAttemptOnlyAfterItsOuterTransactionCommits() {
		jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set status = 'FAILED', claimed_by = null, claimed_at = null, heartbeat_at = null,
				finished_at = coalesce(finished_at, now())
			where status in ('QUEUED', 'RESOLVING', 'GENERATING')
			""".trimIndent(),
		)
		jdbcTemplate.update(
			"""
			update generation_runs
			set status = 'FAILED', claimed_by = null, claimed_at = null, heartbeat_at = null,
				finished_at = coalesce(finished_at, now())
			where status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			""".trimIndent(),
		)
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = 'active', access_mode = 'full' where id = ?",
			devContext.devWorkspaceId,
		)
		val requestId = insertClaimedRequest()
		val scopeId = jdbcTemplate.queryForObject(
			"select source_scope_id from github_release_draft_requests where id = ?",
			UUID::class.java,
			requestId,
		)!!
		val namespaceId = jdbcTemplate.queryForObject(
			"select source_namespace_id from source_scopes where id = ?",
			UUID::class.java,
			scopeId,
		)!!
		val observationId = insertObservationForRequest(requestId)
		val blockId = writingBlockImportService.upsert(
			WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId),
			ImportedWritingBlock(
				sourceNamespaceId = namespaceId,
				sourceScopeId = scopeId,
				observationId = observationId,
				externalObjectKey = "release:test:commit:retry",
				sourceOrigin = "integration",
				sourceKind = "commit",
				title = "Retry source",
				body = "Frozen retry evidence",
				url = "https://github.com/acme/repo/commit/retry",
				canonicalUrl = "https://github.com/acme/repo/commit/retry",
				author = "Plot",
				platform = "github",
				metadata = emptyMap(),
				sourceCreatedAt = Instant.parse("2026-07-30T00:00:00Z"),
				sourceUpdatedAt = Instant.parse("2026-07-30T00:00:00Z"),
			),
		).blockId
		val principal = WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId)
		val firstGeneration = evidenceGenerationBinder.bindAndCreate(
			request = releaseRequest(requestId, scopeId, observationId = null),
			transitionVersion = 1,
			principal = principal,
			evidence = GitHubReleaseEvidence(observationId, listOf(blockId)),
			instruction = "Create a changelog.",
			idempotencyKey = "github-release:retry-test:attempt:0",
		)
		persistence.linkGeneration(requestId, 2, observationId, firstGeneration.runId)
		val oldCreatedAt = Instant.parse("2026-07-01T00:00:00Z")
		exhaustGenerationBudget(firstGeneration.runId, oldCreatedAt)
		persistence.finish(
			requestId,
			transitionVersion = 3,
			status = GitHubReleaseDraftStatus.FAILED,
			errorCode = "GENERATION_FAILED",
		)
		jdbcTemplate.update(
			"update github_release_draft_requests set attempt_count = 99 where id = ?",
			requestId,
		)
		jdbcTemplate.update(
			"update writing_blocks set body = 'Mutable source changed after attempt zero' where id = ?",
			blockId,
		)

		lateinit var retryResult: GitHubReleaseRetryResult
		TransactionTemplate(transactionManager).executeWithoutResult {
			retryResult = retryService.retry(requestId, devContext.devWorkspaceId, 4)
			assertEquals(0, retryDispatchProbe.dispatches.get())
		}

		val row = jdbcTemplate.queryForMap(
			"""
			select id, status, attempt_count, generation_attempt, generation_run_id
			from github_release_draft_requests where id = ?
			""".trimIndent(),
			requestId,
		)
		val retryRunId = row["generation_run_id"] as UUID
		assertEquals(retryRunId, retryResult.generationRunId)
		assertEquals(requestId, row["id"])
		assertEquals("GENERATING", row["status"])
		assertEquals(0, row["attempt_count"])
		assertEquals(1, row["generation_attempt"])
		assertNotEquals(firstGeneration.runId, retryRunId)
		assertEquals(
			2,
			jdbcTemplate.queryForObject(
				"select count(*) from github_release_generation_attempts where request_id = ?",
				Int::class.java,
				requestId,
			),
		)
		val retryCreatedAt = jdbcTemplate.queryForObject(
			"select created_at from generation_runs where id = ?",
			Timestamp::class.java,
			retryRunId,
		)!!.toInstant()
		assertTrue(retryCreatedAt.isAfter(oldCreatedAt))
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from model_invocations where generation_run_id = ?",
				Int::class.java,
				retryRunId,
			),
		)
		assertEquals(
			"github-release:retry-test:attempt:1",
			jdbcTemplate.queryForObject(
				"select idempotency_key from generation_runs where id = ?",
				String::class.java,
				retryRunId,
			),
		)
		assertEquals(
			"Frozen retry evidence",
			generationPersistence.loadState(devContext.devWorkspaceId, retryRunId)
				.evidence.single().snapshotBody,
		)
		assertEquals(1, retryDispatchProbe.dispatches.get())
		assertEquals(retryRunId, retryDispatchProbe.visibleQueuedRunId.get())
	}

	@Test
	fun noActivityTerminalRetainsItsExactIdenticalRangeForAudit() {
		val requestId = insertClaimedRequest()

		persistence.saveResolvedRange(
			requestId,
			transitionVersion = 1,
			baseSha = "same-sha",
			headSha = "same-sha",
			boundaryReason = "PREVIOUS_RELEASE_HEAD",
		)
		persistence.finish(requestId, 2, GitHubReleaseDraftStatus.NO_ACTIVITY)

		val row = jdbcTemplate.queryForMap(
			"""
			select status, base_sha, head_sha, boundary_reason
			from github_release_draft_requests where id = ?
			""".trimIndent(),
			requestId,
		)
		assertEquals("NO_ACTIVITY", row["status"])
		assertEquals("same-sha", row["base_sha"])
		assertEquals("same-sha", row["head_sha"])
		assertEquals("PREVIOUS_RELEASE_HEAD", row["boundary_reason"])
		val sourceScopeId = jdbcTemplate.queryForObject(
			"select source_scope_id from github_release_draft_requests where id = ?",
			UUID::class.java,
			requestId,
		)!!
		val activity = persistence.findActivity(
			requestId,
			sourceScopeId,
			devContext.devWorkspaceId,
		)
		assertEquals("same-sha", activity?.baseSha)
		assertEquals("same-sha", activity?.headSha)
		assertEquals("PREVIOUS_RELEASE_HEAD", activity?.boundaryReason)
	}

	@Test
	fun rollbackDoesNotDispatchOrPersistTheFreshGenerationAttempt() {
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = 'active', access_mode = 'full' where id = ?",
			devContext.devWorkspaceId,
		)
		val requestId = insertClaimedRequest()
		val observationId = insertObservationForRequest(requestId)
		val failedRunId = insertFailedGenerationForRequest(requestId)
		jdbcTemplate.update(
			"update github_release_draft_requests set observation_id = ? where id = ?",
			observationId,
			requestId,
		)
		persistence.linkGeneration(requestId, 1, observationId, failedRunId)
		persistence.finish(requestId, 2, GitHubReleaseDraftStatus.FAILED, "GENERATION_FAILED")

		assertFailsWith<PlannedRetryRollback> {
			TransactionTemplate(transactionManager).executeWithoutResult {
				retryService.retry(requestId, devContext.devWorkspaceId, 3)
				assertEquals(0, retryDispatchProbe.dispatches.get())
				throw PlannedRetryRollback()
			}
		}

		assertEquals(0, retryDispatchProbe.dispatches.get())
		val row = jdbcTemplate.queryForMap(
			"select generation_attempt, generation_run_id from github_release_draft_requests where id = ?",
			requestId,
		)
		assertEquals(0, row["generation_attempt"])
		assertEquals(failedRunId, row["generation_run_id"])
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from github_release_generation_attempts where request_id = ?",
				Int::class.java,
				requestId,
			),
		)
	}

	@Test
	@Transactional
	fun bindingAndGenerationReservationFreezeActualBlockContentInOneTransaction() {
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = 'active', access_mode = 'full' where id = ?",
			devContext.devWorkspaceId,
		)
		val requestId = insertClaimedRequest()
		val scopeId = jdbcTemplate.queryForObject(
			"select source_scope_id from github_release_draft_requests where id = ?",
			UUID::class.java,
			requestId,
		)!!
		val namespaceId = jdbcTemplate.queryForObject(
			"select source_namespace_id from source_scopes where id = ?",
			UUID::class.java,
			scopeId,
		)!!
		val observationId = insertObservationForRequest(requestId)
		val principal = WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId)
		val blockId = writingBlockImportService.upsert(
			principal,
			ImportedWritingBlock(
				sourceNamespaceId = namespaceId,
				sourceScopeId = scopeId,
				observationId = observationId,
				externalObjectKey = "release:test:commit:original",
				sourceOrigin = "integration",
				sourceKind = "commit",
				title = "Original title",
				body = "Original immutable body",
				url = "https://github.com/acme/repo/commit/original",
				canonicalUrl = "https://github.com/acme/repo/commit/original",
				author = "Plot",
				platform = "github",
				metadata = emptyMap(),
				sourceCreatedAt = Instant.parse("2026-07-30T00:00:00Z"),
				sourceUpdatedAt = Instant.parse("2026-07-30T00:00:00Z"),
			),
		).blockId
		val request = releaseRequest(
			requestId = requestId,
			scopeId = scopeId,
			observationId = null,
		)

		val generation = evidenceGenerationBinder.bindAndCreate(
			request = request,
			transitionVersion = 1,
			principal = principal,
			evidence = GitHubReleaseEvidence(observationId, listOf(blockId)),
			instruction = "Create a changelog.",
			idempotencyKey = "test-release-binding-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"update writing_blocks set body = 'Mutated after binding', updated_at = now() where id = ?",
			blockId,
		)

		val frozen = generationPersistence.loadState(devContext.devWorkspaceId, generation.runId)
			.evidence.single()
		assertEquals("Original immutable body", frozen.snapshotBody)
		assertEquals(observationId, persistence.findBoundEvidence(requestId)?.observationId)
	}

	private fun insertObservationForRequest(requestId: UUID): UUID {
		val scopeId = jdbcTemplate.queryForObject(
			"select source_scope_id from github_release_draft_requests where id = ?",
			UUID::class.java,
			requestId,
		)!!
		return UUID.randomUUID().also { observationId ->
			jdbcTemplate.update(
				"""
				insert into source_observations (
				 id, workspace_id, source_scope_id, authority_owner, coverage_key,
				 observation_mode, generation, status, started_at, completed_at, created_at
				) values (?, ?, ?, 'GITHUB_RELEASE', ?, 'PARTIAL', 0, 'COMPLETED', now(), now(), now())
				""".trimIndent(),
				observationId,
				devContext.devWorkspaceId,
				scopeId,
				"test:${UUID.randomUUID()}",
			)
		}
	}

	private fun insertFailedGenerationForRequest(requestId: UUID): UUID {
		val scopeId = jdbcTemplate.queryForObject(
			"select source_scope_id from github_release_draft_requests where id = ?",
			UUID::class.java,
			requestId,
		)!!
		return UUID.randomUUID().also { generationRunId ->
			jdbcTemplate.update(
				"""
				insert into generation_runs (
				 id, workspace_id, source_scope_id, created_by_user_id, idempotency_key, request_fingerprint,
				 status, workflow_version, prompt_version, output_schema_version, budget_version,
				 provider, model_name, budget_snapshot, created_at, updated_at, finished_at
				) values (?, ?, ?, ?, ?, ?, 'FAILED', 'fixed-v1', 'changelog-v8', 'generation-v5',
				 'budget-v1', 'OPENAI', 'test', '{}'::jsonb, now(), now(), now())
				""".trimIndent(),
				generationRunId,
				devContext.devWorkspaceId,
				scopeId,
				devContext.devUserId,
				"failed-${UUID.randomUUID()}",
				"fingerprint-${UUID.randomUUID()}",
			)
			val state = com.plot.api.generation.GenerationWorkflowState(
				runId = generationRunId,
				evidence = emptyList(),
				instruction = "release",
				status = com.plot.api.generation.GenerationRunStatus.FAILED,
				failureCode = "MODEL_PROVIDER_FAILED",
			)
			jdbcTemplate.update(
				"""
				insert into generation_artifacts (
				 id, workspace_id, generation_run_id, artifact_type, artifact_version,
				 sequence_no, payload, created_at
				) values (?, ?, ?, 'FINAL_OUTPUT', 1, 0, ?::jsonb, now())
				""".trimIndent(),
				UUID.randomUUID(),
				devContext.devWorkspaceId,
				generationRunId,
				objectMapper.writeValueAsString(state),
			)
		}
	}

	private fun exhaustGenerationBudget(generationRunId: UUID, createdAt: Instant) {
		val stepId = UUID.randomUUID()
		val now = Instant.parse("2026-07-30T00:00:00Z")
		jdbcTemplate.update(
			"""
			insert into generation_workflow_steps (
			 id, workspace_id, generation_run_id, step_kind, sequence_no,
			 semantic_attempt, status, started_at, finished_at, created_at
			) values (?, ?, ?, 'WRITER', 0, 0, 'FAILED', ?, ?, ?)
			""".trimIndent(),
			stepId,
			devContext.devWorkspaceId,
			generationRunId,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
		)
		jdbcTemplate.update(
			"""
			insert into model_invocations (
			 id, workspace_id, generation_run_id, workflow_step_id, role, logical_call_index,
			 status, provider, model_name, total_token_count, started_at, finished_at, created_at
			) values (?, ?, ?, ?, 'WRITER', 0, 'FAILED', 'OPENAI', 'test', 999999, ?, ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			generationRunId,
			stepId,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
		)
		val failed = generationPersistence.loadState(devContext.devWorkspaceId, generationRunId).copy(
			status = GenerationRunStatus.FAILED,
			failureCode = "MODEL_CALL_BUDGET_EXHAUSTED",
		)
		jdbcTemplate.update(
			"""
			insert into generation_artifacts (
			 id, workspace_id, generation_run_id, artifact_type, artifact_version,
			 sequence_no, payload, created_at
			) values (?, ?, ?, 'FINAL_OUTPUT', 1, 1, ?::jsonb, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			generationRunId,
			objectMapper.writeValueAsString(failed),
			Timestamp.from(now),
		)
		jdbcTemplate.update(
			"""
			update generation_runs
			set status = 'FAILED', error_code = 'MODEL_CALL_BUDGET_EXHAUSTED',
				budget_snapshot = '{"maxModelCalls":12,"maxTotalTokens":100,"maxRunDurationMillis":60000}'::jsonb,
				created_at = ?, updated_at = ?, finished_at = ?
			where id = ?
			""".trimIndent(),
			Timestamp.from(createdAt),
			Timestamp.from(now),
			Timestamp.from(now),
			generationRunId,
		)
	}

	private fun releaseRequest(
		requestId: UUID,
		scopeId: UUID,
		observationId: UUID?,
	) = GitHubReleaseDraftRequest(
		id = requestId,
		workspaceId = devContext.devWorkspaceId,
		sourceScopeId = scopeId,
		initialDeliveryId = jdbcTemplate.queryForObject(
			"select initial_delivery_id from github_release_draft_requests where id = ?",
			UUID::class.java,
			requestId,
		)!!,
		tagName = "v-test",
		baseSha = "base",
		headSha = "head",
		boundaryReason = "PREVIOUS_RELEASE_HEAD",
		status = GitHubReleaseDraftStatus.GENERATING,
		attemptCount = 1,
		transitionVersion = 1,
		generationRunId = null,
		observationId = observationId,
		errorCode = null,
		generationAttempt = 0,
	)

	private fun insertClaimedRequest(): UUID {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val deliveryId = UUID.randomUUID()
		val requestId = UUID.randomUUID()
		val staleAt = Instant.parse("2026-07-30T00:00:00Z")
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId, devContext.devWorkspaceId, "installation-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/repo', 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId, devContext.devWorkspaceId, namespaceId, "repository-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into github_webhook_deliveries
			(id, external_delivery_id, event_type, installation_id, repository_id, payload_hash, disposition, received_at)
			values (?, ?, 'create', 77, 44, ?, 'QUEUED', now())
			""".trimIndent(),
			deliveryId, "delivery-${UUID.randomUUID()}", "a".repeat(64),
		)
		jdbcTemplate.update(
			"""
			insert into github_release_draft_requests
			(id, workspace_id, source_scope_id, initial_delivery_id, tag_name, status, attempt_count,
			 transition_version, claimed_by, claimed_at, heartbeat_at, created_at, updated_at)
			values (?, ?, ?, ?, 'v2', 'RESOLVING', 1, 1, 'dead-worker', ?, ?, now(), now())
			""".trimIndent(),
			requestId,
			devContext.devWorkspaceId,
			scopeId,
			deliveryId,
			Timestamp.from(staleAt),
			Timestamp.from(staleAt),
		)
		return requestId
	}

	private fun countRequests(requestId: UUID): Int = jdbcTemplate.queryForObject(
		"select count(*) from github_release_draft_requests where id = ?",
		Int::class.java,
		requestId,
	) ?: 0

	@TestConfiguration(proxyBeanMethods = false)
	class DispatchConfig {
		@Bean
		@Primary
		fun inertGenerationRunDispatcher(): GenerationRunDispatcher =
			GenerationRunDispatcher(TaskExecutor { }) { false }

		@Bean
		@Primary
		fun commitVisibleGenerationRetryDispatcher(
			jdbcTemplate: JdbcTemplate,
			transactionManager: PlatformTransactionManager,
		): CommitVisibleGenerationRetryDispatcher =
			CommitVisibleGenerationRetryDispatcher(jdbcTemplate, transactionManager)
	}
}

class CommitVisibleGenerationRetryDispatcher(
	private val jdbcTemplate: JdbcTemplate,
	transactionManager: PlatformTransactionManager,
) : GitHubReleaseRetryDispatcher {
	private val requiresNew = TransactionTemplate(transactionManager).apply {
		propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
	}
	val dispatches = AtomicInteger()
	val visibleQueuedRunId = AtomicReference<UUID?>()

	override fun dispatch(runId: UUID) {
		dispatches.incrementAndGet()
		visibleQueuedRunId.set(requiresNew.execute {
			jdbcTemplate.query(
				"select id from generation_runs where id = ? and status = 'QUEUED'",
				{ rs, _ -> rs.getObject("id", UUID::class.java) },
				runId,
			).firstOrNull()
		})
	}

	fun reset() {
		dispatches.set(0)
		visibleQueuedRunId.set(null)
	}
}

private class PlannedRetryRollback : RuntimeException()
