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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GitHubRepositoryMonitoringPersistenceIntegrationTest {
	@Autowired private lateinit var persistence: GitHubRepositoryMonitoringPersistence
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	@BeforeEach
	fun cleanMonitoringData() {
		jdbcTemplate.update(
			"""
			update github_repository_monitoring
			set monitoring_status = 'DISABLED',
			    analysis_status = case when analysis_status = 'ANALYZING' then 'FAILED' else analysis_status end,
			    claimed_by = null, claimed_at = null, next_attempt_at = null, updated_at = now()
			where workspace_id = ? and monitoring_status = 'ACTIVE'
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun competingWorkersClaimOneMonitoringRowExactlyOnce() {
		val monitoring = createMonitoring("competing")
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val claims = listOf("worker-a", "worker-b").map { workerId ->
				executor.submit<GitHubRepositoryMonitoringWorkItem?> {
					start.await()
					persistence.claimNext(workerId, Instant.now())
				}
			}
			start.countDown()
			val results = claims.map { it.get() }

			assertEquals(1, results.count { it?.monitoring?.id == monitoring.id })
			val claimed = assertNotNull(results.single { it != null })
			assertEquals(1L, claimed.monitoring.transitionVersion)
			assertEquals(claimed.monitoring.transitionVersion, version(monitoring.id))
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun staleRecoveryAdvancesVersionAndRejectsLateCompletion() {
		val monitoring = createMonitoring("late-completion")
		val claim = assertNotNull(persistence.claimNext("stale-worker", Instant.now()))
		expire(claim.monitoring.id)

		assertEquals(1, persistence.recoverStaleClaims(Instant.now(), Duration.ofMinutes(2), 5))
		assertEquals(claim.monitoring.transitionVersion + 1, version(monitoring.id))
		val failure = assertFailsWith<InvalidDataAccessApiUsageException> {
			persistence.complete(
				claim.monitoring.id,
				claim.monitoring.transitionVersion,
				"stale-worker",
				GitHubReleaseConventionAnalysis(
					GitHubReleaseConvention.NO_TAGS,
					null,
					null,
					0,
					false,
				),
				Instant.now(),
			)
		}
		assertEquals("Repository monitoring completion was lost", failure.mostSpecificCause.message)
		assertEquals("QUEUED", analysisStatus(monitoring.id))
	}

	@Test
	fun staleRecoveryFailsAtTheConfiguredAttemptLimit() {
		val monitoring = createMonitoring("attempt-limit")
		val claim = assertNotNull(persistence.claimNext("dead-worker", Instant.now()))
		jdbcTemplate.update(
			"""
			update github_repository_monitoring
			set attempt_count = 5, claimed_at = now() - interval '10 minutes'
			where id = ?
			""".trimIndent(),
			claim.monitoring.id,
		)

		assertEquals(1, persistence.recoverStaleClaims(Instant.now(), Duration.ofMinutes(2), 5))
		assertEquals(
			"FAILED:MONITORING_STALE_CLAIM_LIMIT",
			jdbcTemplate.queryForObject(
				"select analysis_status || ':' || last_error_code from github_repository_monitoring where id = ?",
				String::class.java,
				monitoring.id,
			),
		)
	}

	@Test
	fun manualRetryReusesOneRowAndIsIdempotentWhileQueuedOrAnalyzing() {
		val monitoring = createMonitoring("manual-retry")
		jdbcTemplate.update(
			"""
			update github_repository_monitoring
			set analysis_status = 'FAILED', last_error_code = 'MONITORING_ANALYSIS_FAILED'
			where id = ?
			""".trimIndent(),
			monitoring.id,
		)

		val retried = assertNotNull(
			persistence.retry(devContext.devWorkspaceId, monitoring.sourceScopeId, Instant.now()),
		)
		assertEquals(monitoring.id, retried.id)
		assertNull(persistence.retry(devContext.devWorkspaceId, monitoring.sourceScopeId, Instant.now()))

		assertNotNull(persistence.claimNext("retry-worker", Instant.now()))
		assertNull(persistence.retry(devContext.devWorkspaceId, monitoring.sourceScopeId, Instant.now()))
		assertEquals(1, rowCount(monitoring.id))
	}

	private fun createMonitoring(key: String): GitHubRepositoryMonitoringRecord {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into connections (
			  id, workspace_id, provider, connection_kind, external_connection_key,
			  permissions, status, created_by_user_id, created_at, updated_at
			) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, '{}'::jsonb, 'ACTIVE', ?, now(), now())
			""".trimIndent(),
			connectionId,
			devContext.devWorkspaceId,
			positiveExternalId(),
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces (
			  id, workspace_id, provider, namespace_kind, external_namespace_key,
			  display_name, status, created_at, updated_at
			) values (?, ?, 'GITHUB', 'GITHUB_ACCOUNT', ?, 'acme', 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			devContext.devWorkspaceId,
			"acme-$key",
		)
		jdbcTemplate.update(
			"""
			insert into connection_namespace_bindings (
			  id, workspace_id, provider, connection_id, source_namespace_id,
			  capabilities, status, valid_from, created_at, updated_at
			) values (?, ?, 'GITHUB', ?, ?, '{}'::jsonb, 'ACTIVE', now(), now(), now())
			""".trimIndent(),
			bindingId,
			devContext.devWorkspaceId,
			connectionId,
			namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes (
			  id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			  external_scope_key, external_key, display_name, status, created_at, updated_at
			) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', '44', 'acme/plot', ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			"acme/plot-$key",
		)
		return persistence.activate(devContext.devWorkspaceId, scopeId, Instant.now())
	}

	private fun positiveExternalId(): String =
		(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1).toString()

	private fun expire(id: UUID) {
		jdbcTemplate.update(
			"update github_repository_monitoring set claimed_at = now() - interval '10 minutes' where id = ?",
			id,
		)
	}

	private fun version(id: UUID): Long = jdbcTemplate.queryForObject(
		"select transition_version from github_repository_monitoring where id = ?",
		Long::class.java,
		id,
	)!!

	private fun analysisStatus(id: UUID): String = jdbcTemplate.queryForObject(
		"select analysis_status from github_repository_monitoring where id = ?",
		String::class.java,
		id,
	)!!

	private fun rowCount(id: UUID): Int = jdbcTemplate.queryForObject(
		"select count(*) from github_repository_monitoring where id = ?",
		Int::class.java,
		id,
	)!!
}
