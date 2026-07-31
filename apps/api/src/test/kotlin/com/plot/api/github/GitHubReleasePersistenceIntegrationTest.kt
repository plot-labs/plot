package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class GitHubReleasePersistenceIntegrationTest {
	@Autowired private lateinit var persistence: GitHubReleasePersistence
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	@BeforeEach
	fun isolateReleaseQueue() {
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
	}

	@Test
	fun retryRejectsAStaleTransitionVersionWithoutOverwritingTheRequest() {
		val requestId = insertRequest(status = "FAILED")

		persistence.retry(requestId, devContext.devWorkspaceId, transitionVersion = 0)

		assertEquals(ReleaseRow("QUEUED", 1, null), loadReleaseRow(requestId))
		val rejection = assertFailsWith<RuntimeException> {
			persistence.retry(requestId, devContext.devWorkspaceId, transitionVersion = 0)
		}
		assertEquals("GitHubReleaseRetryRejectedException", rejection::class.simpleName)
		assertEquals(ReleaseRow("QUEUED", 1, null), loadReleaseRow(requestId))
	}

	@Test
	fun recoveryTransitionsOnlyStaleClaimsAndAdvancesTheirCapturedVersions() {
		val now = Instant.parse("2026-07-30T00:00:00Z")
		val staleRequestId = insertRequest(status = "RESOLVING")
		val freshRequestId = insertRequest(status = "RESOLVING")
		markClaim(staleRequestId, transitionVersion = 4, heartbeatAt = now.minus(Duration.ofMinutes(10)))
		markClaim(freshRequestId, transitionVersion = 9, heartbeatAt = now.minus(Duration.ofMinutes(1)))

		assertEquals(1, persistence.recoverStaleClaims(now, Duration.ofMinutes(5)))
		assertEquals(ReleaseRow("QUEUED", 5, null), loadReleaseRow(staleRequestId))
		assertEquals(ReleaseRow("RESOLVING", 9, "worker"), loadReleaseRow(freshRequestId))
	}

	@Test
	fun competingWorkersCanClaimOneQueuedRequestOnlyOnce() {
		val requestId = insertRequest(status = "QUEUED")
		val now = Instant.parse("2026-07-30T00:00:00Z")
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val claims = listOf("worker-a", "worker-b").map { workerId ->
				executor.submit<GitHubReleaseDraftRequest?> {
					start.await()
					persistence.claimNext(workerId, now, Duration.ofMinutes(5))
				}
			}
			start.countDown()
			val results = claims.map { it.get() }

			assertEquals(1, results.count { it?.id == requestId })
			assertEquals(1, results.count { it != null })
			val claimedBy = jdbcTemplate.queryForObject(
				"select claimed_by from github_release_draft_requests where id = ?",
				String::class.java,
				requestId,
			)
			assertTrue(claimedBy in setOf("worker-a", "worker-b"))
			assertEquals(ReleaseRow("RESOLVING", 1, claimedBy), loadReleaseRow(requestId))
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun recoveredWorkerCannotCompleteAfterAReplacementClaim() {
		val requestId = insertRequest(status = "QUEUED")
		val claimedAt = Instant.parse("2026-07-30T00:00:00Z")
		val workerA = requireNotNull(
			persistence.claimNext("worker-a", claimedAt, Duration.ofMinutes(2)),
		)
		jdbcTemplate.update(
			"update github_release_draft_requests set heartbeat_at = ? where id = ?",
			java.sql.Timestamp.from(claimedAt.minus(Duration.ofMinutes(10))),
			requestId,
		)
		assertEquals(1, persistence.recoverStaleClaims(claimedAt, Duration.ofMinutes(2)))
		val workerB = requireNotNull(
			persistence.claimNext("worker-b", claimedAt, Duration.ofMinutes(2)),
		)
		val beforeLateCompletion = loadReleaseRow(requestId)

		assertFailsWith<RuntimeException> {
			persistence.saveResolvedRange(
				requestId = requestId,
				transitionVersion = workerA.transitionVersion,
				baseSha = "stale-base",
				headSha = "stale-head",
				boundaryReason = "STALE_WORKER",
			)
		}

		assertEquals(workerA.transitionVersion + 2, workerB.transitionVersion)
		assertEquals(beforeLateCompletion, loadReleaseRow(requestId))
		assertEquals(ReleaseRow("RESOLVING", workerB.transitionVersion, "worker-b"), beforeLateCompletion)
	}

	@Test
	fun concurrentWorkersCannotClaimALaterRequestBeforeItsEarlierScopeRequestFinishes() {
		jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set status = 'FAILED', claimed_by = null, claimed_at = null, heartbeat_at = null
			where workspace_id = ? and status in ('QUEUED', 'RESOLVING', 'GENERATING')
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
		val (earlierRequestId, laterRequestId) = insertAdjacentRequests()
		val now = Instant.parse("2026-07-30T00:00:00Z")
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val claims = listOf("worker-a", "worker-b").map { workerId ->
				executor.submit<GitHubReleaseDraftRequest?> {
					start.await()
					persistence.claimNext(workerId, now, Duration.ofMinutes(5))
				}
			}
			start.countDown()
			val firstWave = claims.map { it.get() }

			assertEquals(listOf(earlierRequestId), firstWave.mapNotNull { it?.id })
			assertEquals(ReleaseRow("QUEUED", 0, null), loadReleaseRow(laterRequestId))

			persistence.saveHeadAndFinishNeedsRange(earlierRequestId, transitionVersion = 1, headSha = "earlier-head")
			val later = persistence.claimNext("worker-c", now, Duration.ofMinutes(5))
			assertEquals(laterRequestId, later?.id)
			persistence.saveHeadAndFinishNeedsRange(laterRequestId, transitionVersion = 1, headSha = "later-head")
		} finally {
			executor.shutdownNow()
		}
	}

	private fun insertRequest(status: String): UUID {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val deliveryId = UUID.randomUUID()
		val requestId = UUID.randomUUID()
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
			(id, external_delivery_id, event_type, payload_hash, disposition, received_at)
			values (?, ?, 'create', ?, 'RECEIVED', now())
			""".trimIndent(),
			deliveryId, "delivery-${UUID.randomUUID()}", "a".repeat(64),
		)
		jdbcTemplate.update(
			"""
			insert into github_release_draft_requests
			(id, workspace_id, source_scope_id, initial_delivery_id, tag_name, status, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, now(), now())
			""".trimIndent(),
			requestId, devContext.devWorkspaceId, scopeId, deliveryId, "v-${UUID.randomUUID()}", status,
		)
		return requestId
	}

	private fun markClaim(requestId: UUID, transitionVersion: Long, heartbeatAt: Instant) {
		jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set transition_version = ?, claimed_by = 'worker', claimed_at = ?, heartbeat_at = ?
			where id = ?
			""".trimIndent(),
			transitionVersion, java.sql.Timestamp.from(heartbeatAt), java.sql.Timestamp.from(heartbeatAt), requestId,
		)
	}

	private fun insertAdjacentRequests(): Pair<UUID, UUID> {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val earlierDeliveryId = UUID.randomUUID()
		val laterDeliveryId = UUID.randomUUID()
		val earlierRequestId = UUID.randomUUID()
		val laterRequestId = UUID.randomUUID()
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
		listOf(earlierDeliveryId, laterDeliveryId).forEach { deliveryId ->
			jdbcTemplate.update(
				"""
				insert into github_webhook_deliveries
				(id, external_delivery_id, event_type, payload_hash, disposition, received_at)
				values (?, ?, 'create', ?, 'RECEIVED', now())
				""".trimIndent(),
				deliveryId, "delivery-${UUID.randomUUID()}", "a".repeat(64),
			)
		}
		jdbcTemplate.update(
			"""
			insert into github_release_draft_requests
			(id, workspace_id, source_scope_id, initial_delivery_id, tag_name, status, created_at, updated_at)
			values
			(?, ?, ?, ?, 'v1', 'QUEUED', '2026-07-30 00:00:00+00', '2026-07-30 00:00:00+00'),
			(?, ?, ?, ?, 'v2', 'QUEUED', '2026-07-30 00:01:00+00', '2026-07-30 00:01:00+00')
			""".trimIndent(),
			earlierRequestId, devContext.devWorkspaceId, scopeId, earlierDeliveryId,
			laterRequestId, devContext.devWorkspaceId, scopeId, laterDeliveryId,
		)
		return earlierRequestId to laterRequestId
	}

	private fun loadReleaseRow(requestId: UUID): ReleaseRow = jdbcTemplate.queryForObject(
		"select status, transition_version, claimed_by from github_release_draft_requests where id = ?",
		{ rs, _ -> ReleaseRow(rs.getString(1), rs.getLong(2), rs.getString(3)) },
		requestId,
	)!!
}

private data class ReleaseRow(val status: String, val transitionVersion: Long, val claimedBy: String?)
