package com.plot.api.recovery

import com.plot.api.TestcontainersConfiguration
import com.plot.api.artifact.workflow.ArtifactWorkflowRunDispatcher
import com.plot.api.config.PlotAiProperties
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.github.GitHubProperties
import com.plot.api.github.GitHubReleaseDraftDispatcher
import com.plot.api.github.GitHubReleaseDraftStatus
import com.plot.api.github.GitHubReleaseLeasePersistence
import com.plot.api.routine.AgentRunDispatcher
import com.plot.api.routine.RoutineAgentProperties
import com.plot.api.routine.RoutineRunDispatcher
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class RecoveryCoordinatorIntegrationTest {

	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var persistence: RecoveryCoordinatorPersistence
	@Autowired private lateinit var releaseLeasePersistence: GitHubReleaseLeasePersistence

	private val now = Instant.parse("2026-09-09T12:00:00Z")
	private val clock = Clock.fixed(now, ZoneOffset.UTC)
	private lateinit var sourceScopeId: UUID

	@BeforeEach
	fun setup() {
		devBootstrapService.bootstrap()
		jdbcTemplate.update("delete from github_release_draft_requests where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from github_webhook_deliveries")
		jdbcTemplate.update("delete from source_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_namespaces where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connections where workspace_id = ?", devContext.devWorkspaceId)
		sourceScopeId = bindRepository()
	}

	@Test
	fun `R-002 - tick discovers dropped wakeup and triggers dispatch`() {
		val deliveryId = insertDelivery()
		val requestId = UUID.randomUUID()
		insertReleaseRequest(requestId, deliveryId, sourceScopeId, status = "QUEUED", nextAttemptAt = null)

		val dispatches = AtomicInteger()
		val coordinator = createCoordinator(
			properties = RecoveryProperties(enabled = true),
			onReleaseDispatch = { dispatches.incrementAndGet() },
		)

		val summary = coordinator.tick()

		assertEquals(1, summary.queues["release"]?.runnableCount)
		assertTrue(summary.queues["release"]?.dispatched == true)
		assertEquals(1, dispatches.get())

		// Now claim the discovered request using existing lease logic
		val claimed = releaseLeasePersistence.claimNext("worker-1", now, Duration.ofMinutes(2))
		assertNotNull(claimed)
		assertEquals(requestId, claimed.id)
		assertEquals(GitHubReleaseDraftStatus.RESOLVING, claimed.status)
	}

	@Test
	fun `R-003 - unexpired lease is protected and stale version updates are rejected`() {
		val deliveryId = insertDelivery()
		val requestId = UUID.randomUUID()
		insertReleaseRequest(requestId, deliveryId, sourceScopeId, status = "QUEUED")

		// Worker A claims with valid 5-minute lease
		val claimedA = releaseLeasePersistence.claimNext("worker-A", now, Duration.ofMinutes(5))
		assertNotNull(claimedA)
		val v1 = claimedA.transitionVersion

		// Worker B tries to claim simultaneously while lease is valid -> must be rejected (null)
		val claimedBWhileValid = releaseLeasePersistence.claimNext("worker-B", now.plusSeconds(30), Duration.ofMinutes(5))
		assertNull(claimedBWhileValid, "Simultaneous claim on valid lease must be rejected")

		// Advance clock past lease expiration (worker A died/hung)
		val expiredNow = now.plus(Duration.ofMinutes(6))
		val claimedBAfterExpiry = releaseLeasePersistence.claimNext("worker-B", expiredNow, Duration.ofMinutes(5))
		assertNotNull(claimedBAfterExpiry, "Worker B should claim after lease expiration")
		assertEquals(requestId, claimedBAfterExpiry.id)
		val currentClaimedBy = jdbcTemplate.queryForObject(
			"select claimed_by from github_release_draft_requests where id = ?", String::class.java, requestId
		)
		assertEquals("worker-B", currentClaimedBy)

		// Worker A tries to finish with stale transition version -> must be rejected!
		assertFailsWith<InvalidDataAccessApiUsageException> {
			releaseLeasePersistence.finish(requestId, v1, GitHubReleaseDraftStatus.READY, null)
		}
	}

	@Test
	fun `R-004 - future retry time is not runnable until due`() {
		val deliveryId = insertDelivery()
		val requestId = UUID.randomUUID()
		// nextAttemptAt is 1 hour in the future
		insertReleaseRequest(requestId, deliveryId, sourceScopeId, status = "QUEUED", nextAttemptAt = now.plus(Duration.ofHours(1)))

		val dispatches = AtomicInteger()
		val coordinator = createCoordinator(
			properties = RecoveryProperties(enabled = true),
			onReleaseDispatch = { dispatches.incrementAndGet() },
		)

		val summary = coordinator.tick()

		assertEquals(0, summary.queues["release"]?.runnableCount)
		assertEquals(0, dispatches.get())

		// Claim should also return null before due
		assertNull(releaseLeasePersistence.claimNext("worker-1", now, Duration.ofMinutes(2)))
	}

	@Test
	fun `R-007 - bounded recovery limits batch size`() {
		val deliveryId = insertDelivery()
		for (i in 1..5) {
			val scope = bindRepository(repoNumber = 100L + i)
			insertReleaseRequest(UUID.randomUUID(), deliveryId, scope, status = "QUEUED", nextAttemptAt = null)
		}

		// batchSize = 2
		val stats = persistence.findReleaseDraftStats(Instant.now(), Instant.now().minus(Duration.ofMinutes(2)), batchSize = 2)
		assertEquals(2, stats.runnableCount)
	}

	@Test
	fun `R-008 - terminal statuses are not eligible for recovery`() {
		val deliveryId = insertDelivery()
		insertReleaseRequest(UUID.randomUUID(), deliveryId, sourceScopeId, status = "READY")
		insertReleaseRequest(UUID.randomUUID(), deliveryId, sourceScopeId, status = "NO_ACTIVITY")
		insertReleaseRequest(UUID.randomUUID(), deliveryId, sourceScopeId, status = "FAILED")

		val dispatches = AtomicInteger()
		val coordinator = createCoordinator(
			properties = RecoveryProperties(enabled = true),
			onReleaseDispatch = { dispatches.incrementAndGet() },
		)

		val summary = coordinator.tick()

		assertEquals(0, summary.queues["release"]?.runnableCount)
		assertEquals(0, dispatches.get())
	}

	@Test
	fun `R-009 - release order preservation denies following request while predecessor is incomplete`() {
		val deliveryId = insertDelivery()
		val req1 = UUID.randomUUID()
		val req2 = UUID.randomUUID()
		val past = Instant.now().minusSeconds(200)
		// req1 created earlier
		insertReleaseRequest(req1, deliveryId, sourceScopeId, status = "QUEUED", createdAt = past)
		// req2 created later for the same source scope
		insertReleaseRequest(req2, deliveryId, sourceScopeId, status = "QUEUED", createdAt = past.plusSeconds(50))

		val current = Instant.now()
		// Under existing predecessor rules, only req1 is runnable
		val stats = persistence.findReleaseDraftStats(current, current.minus(Duration.ofMinutes(2)), batchSize = 10)
		assertEquals(1, stats.runnableCount)

		val claimed = releaseLeasePersistence.claimNext("worker-1", current, Duration.ofMinutes(2))
		assertNotNull(claimed)
		assertEquals(req1, claimed.id)

		// While req1 is in RESOLVING, req2 is not claimable
		assertNull(releaseLeasePersistence.claimNext("worker-2", current, Duration.ofMinutes(2)))

		// Complete req1 as READY
		releaseLeasePersistence.finish(req1, claimed.transitionVersion, GitHubReleaseDraftStatus.READY, null)

		// Now req2 becomes runnable
		val statsAfter = persistence.findReleaseDraftStats(current, current.minus(Duration.ofMinutes(2)), batchSize = 10)
		assertEquals(1, statsAfter.runnableCount)
		val claimed2 = releaseLeasePersistence.claimNext("worker-2", current, Duration.ofMinutes(2))
		assertNotNull(claimed2)
		assertEquals(req2, claimed2.id)
	}

	private fun createCoordinator(
		properties: RecoveryProperties,
		onReleaseDispatch: () -> Unit = {},
	): RecoveryCoordinator {
		return RecoveryCoordinator(
			properties = properties,
			persistence = persistence,
			releaseDispatcher = GitHubReleaseDraftDispatcher { onReleaseDispatch() },
			routineRunDispatcher = makeMockRoutineDispatcher { },
			agentRunDispatcher = makeMockAgentDispatcher { },
			artifactWorkflowDispatcher = makeMockArtifactDispatcher { },
			gitHubProperties = GitHubProperties(releaseAutomationEnabled = true),
			routineAgentProperties = RoutineAgentProperties(workersEnabled = true),
			plotAiProperties = PlotAiProperties(workerEnabled = true),
			clock = clock,
			meterRegistry = SimpleMeterRegistry(),
		)
	}

	private fun bindRepository(repoNumber: Long = 99L): UUID {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at)
				values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())""".trimIndent(),
			connectionId, devContext.devWorkspaceId, "$repoNumber", devContext.devUserId,
		)
		jdbcTemplate.update(
			"""insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
				values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'ACTIVE', now(), now())""".trimIndent(),
			namespaceId, devContext.devWorkspaceId, "repository:$repoNumber",
		)
		jdbcTemplate.update(
			"""insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at)
				values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())""".trimIndent(),
			bindingId, devContext.devWorkspaceId, connectionId, namespaceId,
		)
		jdbcTemplate.update(
			"""insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, external_key, display_name, metadata, status, created_at, updated_at)
				values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, ?, '{"defaultBranch":"main"}'::jsonb, 'ACTIVE', now(), now())""".trimIndent(),
			scopeId, devContext.devWorkspaceId, namespaceId, "$repoNumber", "acme/repo-$repoNumber", "acme/repo-$repoNumber",
		)
		return scopeId
	}

	private fun insertDelivery(): UUID {
		val id = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into github_webhook_deliveries (
				id, external_delivery_id, event_type, event_action, installation_id, repository_id,
				payload_hash, disposition, received_at
			) values (?, ?, 'release', 'published', 77, 99, ?, 'QUEUED', now())
			""".trimIndent(),
			id, "ext-$id", "a".repeat(64),
		)
		return id
	}

	private fun insertReleaseRequest(
		id: UUID,
		deliveryId: UUID,
		scopeId: UUID,
		status: String,
		nextAttemptAt: Instant? = null,
		createdAt: Instant = now,
		tagName: String = "v1.0.${UUID.randomUUID()}",
	) {
		jdbcTemplate.update(
			"""
			insert into github_release_draft_requests (
				id, workspace_id, source_scope_id, initial_delivery_id, tag_name, status,
				next_attempt_at, transition_version, attempt_count, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?)
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			scopeId,
			deliveryId,
			tagName,
			status,
			nextAttemptAt?.let(Timestamp::from),
			Timestamp.from(createdAt),
			Timestamp.from(createdAt),
		)
	}

	private fun makeMockRoutineDispatcher(onDispatch: () -> Unit): RoutineRunDispatcher {
		return object : RoutineRunDispatcher(
			taskExecutor = org.springframework.core.task.SyncTaskExecutor(),
			worker = org.mockito.Mockito.mock(com.plot.api.routine.RoutineWorker::class.java),
			agentProperties = RoutineAgentProperties(),
			retryExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(),
		) {
			override fun dispatch() {
				onDispatch()
			}
		}
	}

	private fun makeMockAgentDispatcher(onDispatch: () -> Unit): AgentRunDispatcher {
		return object : AgentRunDispatcher(
			taskExecutor = org.springframework.core.task.SyncTaskExecutor(),
			worker = org.mockito.Mockito.mock(com.plot.api.routine.AgentRunWorker::class.java),
			properties = RoutineAgentProperties(),
			retryExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(),
		) {
			override fun dispatch() {
				onDispatch()
			}
		}
	}

	private fun makeMockArtifactDispatcher(onDispatch: () -> Unit): ArtifactWorkflowRunDispatcher {
		return ArtifactWorkflowRunDispatcher(
			taskExecutor = org.springframework.core.task.SyncTaskExecutor(),
			drainBatch = {
				onDispatch()
				false
			},
		)
	}
}
