package com.plot.api.artifact.workflow

import com.plot.api.TestcontainersConfiguration
import com.plot.api.ai.provider.ModelRole
import com.plot.api.dev.DevContext
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.SourceProvider
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
class ArtifactWorkflowPersistenceReliabilityIntegrationTest {
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
	fun competingWorkersClaimOneQueuedRunExactlyOnce() {
		val state = reserve("competing-claim")
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val futures = listOf("worker-a", "worker-b").map { workerId ->
				executor.submit<ClaimedArtifactWorkflowRun?> {
					start.await()
					persistence.claimNext(workerId, Instant.now().minusSeconds(120))
				}
			}

			start.countDown()
			val claims = futures.map { it.get() }

			assertEquals(1, claims.count { it?.runId == state.runId })
			assertEquals(1L, claims.single { it != null }?.transitionVersion)
			assertEquals(1L, transitionVersion(state.runId))
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun claimAndStaleRecoveryEachAdvanceTransitionVersion() {
		val state = reserve("claim-epoch")
		val first = assertNotNull(persistence.claimNext("stable-worker-id", Instant.now().minusSeconds(120)))
		assertEquals(1L, first.transitionVersion)

		expireClaim(state.runId)
		assertEquals(1, persistence.recoverStaleClaims(Instant.now().minusSeconds(120)))
		assertEquals(2L, transitionVersion(state.runId))

		val replacement = assertNotNull(
			persistence.claimNext("stable-worker-id", Instant.now().minusSeconds(120)),
		)
		assertEquals(3L, replacement.transitionVersion)
	}

	@Test
	fun renewalRequiresTheCurrentOwnerVersionAndRunnableState() {
		val state = reserve("claim-renewal")
		val first = assertNotNull(persistence.claimNext("stable-worker-id", Instant.now().minusSeconds(120)))
		assertEquals(true, persistence.renewClaim(first, Instant.now()))

		expireClaim(state.runId)
		assertEquals(1, persistence.recoverStaleClaims(Instant.now().minusSeconds(120)))
		val replacement = assertNotNull(
			persistence.claimNext("stable-worker-id", Instant.now().minusSeconds(120)),
		)

		assertEquals(false, persistence.renewClaim(first, Instant.now()))
		assertEquals(true, persistence.renewClaim(replacement, Instant.now()))
	}

	@Test
	fun staleClaimCannotWriteAfterRecoveryAndSameWorkerIdReclaim() {
		val state = reserve("stale-write")
		val staleClaim = assertNotNull(
			persistence.claimNext("stable-worker-id", Instant.now().minusSeconds(120)),
		)
		val invocation = persistence.beginInvocation(staleClaim, ModelRole.WRITER)
		expireClaim(state.runId)

		assertEquals(1, persistence.recoverStaleClaims(Instant.now().minusSeconds(120)))
		assertEquals(
			"FAILED:LEASE_LOST_OUTCOME_UNKNOWN",
			jdbcTemplate.queryForObject(
				"select status || ':' || failure_code from model_invocations where id = ?",
				String::class.java,
				invocation.id,
			),
		)
		val replacement = assertNotNull(
			persistence.claimNext("stable-worker-id", Instant.now().minusSeconds(120)),
		)
		assertEquals(3L, replacement.transitionVersion)
		val before = runSnapshot(state.runId)

		assertLost { persistence.beginInvocation(staleClaim, ModelRole.WRITER) }
		assertLost { persistence.failClaim(staleClaim, state, "STALE_FAILURE") }
		assertLost { persistence.failCheckpoint(staleClaim, invocation, state, "STALE_INVOCATION_FAILURE") }
		assertLost { persistence.completeCheckpoint(staleClaim, invocation, state, null) }

		assertEquals(before, runSnapshot(state.runId))
		assertEquals(1, invocationCount(state.runId))
		assertNull(
			jdbcTemplate.queryForObject(
				"select error_code from generation_runs where id = ?",
				String::class.java,
				state.runId,
			),
		)
	}

	@Test
	fun sourceAccessFenceFailsTheRunAndRejectsItsLateCheckpoint() {
		val sourceScopeId = insertSourceScope("access-loss")
		val state = reserve("access-loss", sourceScopeId)
		val claim = assertNotNull(
			persistence.claimNext("access-loss-worker", Instant.now().minusSeconds(120)),
		)
		val invocation = persistence.beginInvocation(claim, ModelRole.WRITER)

		assertEquals(1, persistence.fenceSourceScope(devContext.devWorkspaceId, sourceScopeId, Instant.now()))
		assertEquals(
			"FAILED:SOURCE_ACCESS_LOST",
			jdbcTemplate.queryForObject(
				"select status || ':' || error_code from generation_runs where id = ?",
				String::class.java,
				state.runId,
			),
		)
		assertEquals(
			"FAILED:SOURCE_ACCESS_LOST",
			jdbcTemplate.queryForObject(
				"select status || ':' || failure_code from model_invocations where id = ?",
				String::class.java,
				invocation.id,
			),
		)

		val before = runSnapshot(state.runId)
		assertLost { persistence.completeCheckpoint(claim, invocation, state, null) }
		assertEquals(before, runSnapshot(state.runId))
	}

	private fun assertLost(action: () -> Unit) {
		val failure = assertFailsWith<IllegalStateException> { action() }
		assertEquals("ArtifactWorkflow run claim was lost", failure.message)
	}

	private fun reserve(key: String, sourceScopeId: UUID? = null): ArtifactWorkflowState {
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
				sourceScopeId = sourceScopeId,
				idempotencyKey = "reliability-$key-${UUID.randomUUID()}",
				requestFingerprint = "fingerprint-$key",
				state = state,
				provider = "OPENAI",
				modelName = "scripted",
				budgetJson = """{"maxModelCalls":12,"maxTotalTokens":80000,"maxRunDurationMillis":300000}""",
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

	private fun insertSourceScope(key: String): UUID {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			devContext.devWorkspaceId,
			"installation-$key-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			"repository-$key",
			"acme/$key",
		)
		return scopeId
	}

	private fun expireClaim(runId: UUID) {
		jdbcTemplate.update(
			"update generation_runs set heartbeat_at = now() - interval '10 minutes' where id = ?",
			runId,
		)
	}

	private fun transitionVersion(runId: UUID): Long = jdbcTemplate.queryForObject(
		"select transition_version from generation_runs where id = ?",
		Long::class.java,
		runId,
	)!!

	private fun invocationCount(runId: UUID): Int = jdbcTemplate.queryForObject(
		"select count(*) from model_invocations where generation_run_id = ?",
		Int::class.java,
		runId,
	)!!

	private fun runSnapshot(runId: UUID): RunSnapshot = jdbcTemplate.queryForObject(
		"""
		select status, transition_version, claimed_by,
		       (select count(*) from generation_artifacts where generation_run_id = generation_runs.id),
		       (select count(*) from content_packs where generation_run_id = generation_runs.id)
		from generation_runs
		where id = ?
		""".trimIndent(),
		{ rs, _ ->
			RunSnapshot(
				status = rs.getString(1),
				transitionVersion = rs.getLong(2),
				claimedBy = rs.getString(3),
				generationArtifactCount = rs.getInt(4),
				storedArtifactCount = rs.getInt(5),
			)
		},
		runId,
	)
}

private data class RunSnapshot(
	val status: String,
	val transitionVersion: Long,
	val claimedBy: String?,
	val generationArtifactCount: Int,
	val storedArtifactCount: Int,
)
