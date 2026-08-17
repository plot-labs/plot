package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import com.plot.api.artifact.workflow.ArtifactWorkflowRunDispatcher
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
	@Autowired private lateinit var leasePersistence: GitHubReleaseLeaseStore
	@Autowired private lateinit var requestPersistence: GitHubReleaseRequestStore
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var objectMapper: ObjectMapper
	@Autowired private lateinit var retryService: GitHubReleaseRetryService
	@Autowired private lateinit var retryDispatchProbe: CommitVisibleArtifactWorkflowRetryDispatcher
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

		assertEquals(1, leasePersistence.recoverStaleClaims(now, Duration.ofMinutes(2)))
		val recovered = leasePersistence.claimNext("replacement-worker", now, Duration.ofMinutes(2))

		assertEquals(requestId, recovered?.id)
		assertEquals(2, recovered?.attemptCount)
		assertEquals(3, recovered?.transitionVersion)
		assertEquals(1, countRequests(requestId))
	}

	@Test
	fun delayedRetryKeepsTheSameRequestAndCannotRunBeforeNextAttempt() {
		val requestId = insertClaimedRequest()
		val retryAt = Instant.parse("2026-07-30T00:15:00Z")

		leasePersistence.scheduleRetry(
			requestId = requestId,
			transitionVersion = 1,
			nextAttemptAt = retryAt,
			errorCode = "GITHUB_RATE_LIMITED",
		)

		assertEquals(null, leasePersistence.claimNext("early-worker", retryAt.minusSeconds(1), Duration.ofMinutes(2)))
		assertEquals(requestId, leasePersistence.claimNext("ready-worker", retryAt, Duration.ofMinutes(2))?.id)
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

		assertEquals(1, leasePersistence.recoverStaleClaims(now, Duration.ofMinutes(2)))
		val recovered = leasePersistence.claimNext("replacement-worker", now, Duration.ofMinutes(2))

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

		assertEquals(true, leasePersistence.renewClaim(requestId, 1, "dead-worker", heartbeatAt))
		assertEquals(0, leasePersistence.recoverStaleClaims(heartbeatAt.plusSeconds(60), Duration.ofMinutes(2)))
		assertEquals(1, leasePersistence.recoverStaleClaims(heartbeatAt.plusSeconds(180), Duration.ofMinutes(2)))
		val replacement = leasePersistence.claimNext(
			"replacement-worker",
			heartbeatAt.plusSeconds(180),
			Duration.ofMinutes(2),
		)

		assertEquals(requestId, replacement?.id)
		assertEquals(false, leasePersistence.renewClaim(requestId, 1, "dead-worker", heartbeatAt.plusSeconds(181)))
		assertEquals(true, leasePersistence.renewClaim(
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
		val artifactWorkflowRunId = insertFailedArtifactWorkflowForRequest(requestId)
		jdbcTemplate.update(
			"update github_release_draft_requests set observation_id = ? where id = ?",
			observationId,
			requestId,
		)

		jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set observation_id = ?, generation_run_id = ?, status = 'GENERATING', transition_version = 2,
				claimed_by = null, claimed_at = null, heartbeat_at = null
			where id = ?
			""".trimIndent(),
			observationId,
			artifactWorkflowRunId,
			requestId,
		)

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
	fun noActivityTerminalRetainsItsExactIdenticalRangeForAudit() {
		val requestId = insertClaimedRequest()

		requestPersistence.saveResolvedRange(
			requestId,
			transitionVersion = 1,
			baseSha = "same-sha",
			headSha = "same-sha",
			boundaryReason = "PREVIOUS_RELEASE_HEAD",
		)
		leasePersistence.finish(requestId, 2, GitHubReleaseDraftStatus.NO_ACTIVITY)

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
		val activity = requestPersistence.findActivity(
			requestId,
			sourceScopeId,
			devContext.devWorkspaceId,
		)
		assertEquals("same-sha", activity?.baseSha)
		assertEquals("same-sha", activity?.headSha)
		assertEquals("PREVIOUS_RELEASE_HEAD", activity?.boundaryReason)
	}

	@Test
	fun rollbackDoesNotDispatchOrPersistTheFreshArtifactWorkflowAttempt() {
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = 'active', access_mode = 'full' where id = ?",
			devContext.devWorkspaceId,
		)
		val requestId = insertClaimedRequest()
		val observationId = insertObservationForRequest(requestId)
		val failedRunId = insertFailedArtifactWorkflowForRequest(requestId)
		jdbcTemplate.update(
			"update github_release_draft_requests set observation_id = ? where id = ?",
			observationId,
			requestId,
		)
		jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set observation_id = ?, generation_run_id = ?, status = 'GENERATING', transition_version = 2,
				claimed_by = null, claimed_at = null, heartbeat_at = null
			where id = ?
			""".trimIndent(),
			observationId,
			failedRunId,
			requestId,
		)
		leasePersistence.finish(requestId, 2, GitHubReleaseDraftStatus.FAILED, "AGENT_RUN_FAILED")

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
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from github_release_generation_attempts where request_id = ?",
				Int::class.java,
				requestId,
			),
		)
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

	private fun insertFailedArtifactWorkflowForRequest(requestId: UUID): UUID {
		val scopeId = jdbcTemplate.queryForObject(
			"select source_scope_id from github_release_draft_requests where id = ?",
			UUID::class.java,
			requestId,
		)!!
		return UUID.randomUUID().also { artifactWorkflowRunId ->
			jdbcTemplate.update(
				"""
				insert into generation_runs (
				 id, workspace_id, source_scope_id, created_by_user_id, idempotency_key, request_fingerprint,
				 status, workflow_version, prompt_version, output_schema_version, budget_version,
				 provider, model_name, budget_snapshot, created_at, updated_at, finished_at
				) values (?, ?, ?, ?, ?, ?, 'FAILED', 'fixed-v1', 'changelog-v8', 'generation-v5',
				 'budget-v1', 'OPENAI', 'test', '{}'::jsonb, now(), now(), now())
				""".trimIndent(),
				artifactWorkflowRunId,
				devContext.devWorkspaceId,
				scopeId,
				devContext.devUserId,
				"failed-${UUID.randomUUID()}",
				"fingerprint-${UUID.randomUUID()}",
			)
			val state = com.plot.api.artifact.workflow.ArtifactWorkflowState(
				runId = artifactWorkflowRunId,
				evidence = emptyList(),
				instruction = "release",
				status = com.plot.api.artifact.workflow.ArtifactWorkflowRunStatus.FAILED,
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
				artifactWorkflowRunId,
				objectMapper.writeValueAsString(state),
			)
		}
	}

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
		fun inertArtifactWorkflowRunDispatcher(): ArtifactWorkflowRunDispatcher =
			ArtifactWorkflowRunDispatcher(TaskExecutor { }) { false }

		@Bean
		@Primary
		fun commitVisibleArtifactWorkflowRetryDispatcher(
			jdbcTemplate: JdbcTemplate,
			transactionManager: PlatformTransactionManager,
		): CommitVisibleArtifactWorkflowRetryDispatcher =
			CommitVisibleArtifactWorkflowRetryDispatcher(jdbcTemplate, transactionManager)
	}
}

class CommitVisibleArtifactWorkflowRetryDispatcher(
	private val jdbcTemplate: JdbcTemplate,
	transactionManager: PlatformTransactionManager,
) : GitHubReleaseRetryDispatcher {
	private val requiresNew = TransactionTemplate(transactionManager).apply {
		propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
	}
	val dispatches = AtomicInteger()
	val visibleQueuedRunId = AtomicReference<UUID?>()

	override fun dispatch() {
		dispatches.incrementAndGet()
		visibleQueuedRunId.set(requiresNew.execute {
			jdbcTemplate.query(
				"select id from github_release_draft_requests where status = 'QUEUED' order by updated_at desc limit 1",
				{ rs, _ -> rs.getObject("id", UUID::class.java) },
			).firstOrNull()
		})
	}

	fun reset() {
		dispatches.set(0)
		visibleQueuedRunId.set(null)
	}
}

private class PlannedRetryRollback : RuntimeException()
