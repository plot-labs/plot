package com.plot.api

import com.plot.api.dev.DevContext
import com.plot.api.github.GitHubClient
import com.plot.api.github.GitHubPullRequest
import com.plot.api.github.GitHubRepository
import com.plot.api.github.GitHubRepositoryMonitoringDispatcher
import com.plot.api.github.GitHubRepositoryMonitoringPersistence
import com.plot.api.github.GitHubTagPage
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.util.ReflectionTestUtils
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class WorkerShutdownIntegrationTest {
	@Autowired private lateinit var postgres: PostgreSQLContainer
	@Autowired private lateinit var verificationJdbcTemplate: JdbcTemplate

	@Test
	fun contextCloseWaitsForInFlightMonitoringBeforeClosingTheDatasource(output: CapturedOutput) {
		val context = SpringApplicationBuilder(ApiApplication::class.java, ShutdownTestConfig::class.java)
			.web(WebApplicationType.NONE)
			.properties(
				"spring.datasource.url=${postgres.jdbcUrl}",
				"spring.datasource.username=${postgres.username}",
				"spring.datasource.password=${postgres.password}",
				"plot.dev-bootstrap.enabled=true",
				"plot.github.enabled=true",
				"plot.github.monitoring-analysis-poll-delay=1h",
				"plot.github.release-automation-enabled=false",
				"plot.ai.worker-poll-delay=1h",
			)
			.run()
		val client = context.getBean(BlockingShutdownGitHubClient::class.java)
		val dispatcher = context.getBean(GitHubRepositoryMonitoringDispatcher::class.java)
		val executor = context.getBean(
			"githubRepositoryMonitoringTaskExecutor",
			ThreadPoolTaskExecutor::class.java,
		)
		val managedExecutors = listOf(
			executor,
			context.getBean("artifactWorkflowTaskExecutor", ThreadPoolTaskExecutor::class.java),
			context.getBean("githubReleaseTaskExecutor", ThreadPoolTaskExecutor::class.java),
		)
		val monitoringId = createMonitoring(
			context.getBean(JdbcTemplate::class.java),
			context.getBean(DevContext::class.java),
			context.getBean(GitHubRepositoryMonitoringPersistence::class.java),
		)
		val closeFinished = CountDownLatch(1)
		val closeFailure = AtomicReference<Throwable?>()
		val closeStartedAt = AtomicReference<Long>()
		val closeFinishedAt = AtomicReference<Long>()

		dispatcher.dispatch()
		assertTrue(client.entered.await(2, TimeUnit.SECONDS), "monitoring provider call did not start")
		val closing = Thread {
			closeStartedAt.set(System.nanoTime())
			try {
				context.close()
			} catch (failure: Throwable) {
				closeFailure.set(failure)
			} finally {
				closeFinishedAt.set(System.nanoTime())
				closeFinished.countDown()
			}
		}.apply { start() }

		try {
			assertTrue(awaitExecutorShutdown(executor), "monitoring executor did not begin shutdown")
			dispatcher.dispatch()
			assertEquals(1, client.calls.get())
			assertFalse(
				closeFinished.await(1, TimeUnit.SECONDS),
				"context closed while monitoring work was still in flight",
			)
		} finally {
			client.release.countDown()
		}

		assertTrue(closeFinished.await(3, TimeUnit.SECONDS), "context close exceeded its bound")
		closing.join(1_000)
		assertNull(closeFailure.get())
		assertTrue(
			closeFinishedAt.get() - closeStartedAt.get() < TimeUnit.SECONDS.toNanos(3),
			"context close exceeded three seconds",
		)
		assertEquals(
			"COMPLETED",
			verificationJdbcTemplate.queryForObject(
				"select analysis_status from github_repository_monitoring where id = ?",
				String::class.java,
				monitoringId,
			),
		)
		managedExecutors.forEach { managed ->
			assertEquals(true, ReflectionTestUtils.getField(managed, "strictEarlyShutdown"))
			assertEquals(true, ReflectionTestUtils.getField(managed, "waitForTasksToCompleteOnShutdown"))
			assertTrue((ReflectionTestUtils.getField(managed, "awaitTerminationMillis") as Long) > 0)
		}
		TEARDOWN_ERROR_SIGNATURES.forEach { signature ->
			assertFalse(output.all.contains(signature), "teardown logs contained $signature")
		}
	}

	private fun awaitExecutorShutdown(executor: ThreadPoolTaskExecutor): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
		while (System.nanoTime() < deadline) {
			if (executor.threadPoolExecutor.isShutdown) return true
			Thread.sleep(10)
		}
		return false
	}

	private fun createMonitoring(
		jdbcTemplate: JdbcTemplate,
		devContext: DevContext,
		persistence: GitHubRepositoryMonitoringPersistence,
	): UUID {
		val key = UUID.randomUUID()
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into connections (
			  id, workspace_id, provider, connection_kind, external_connection_key,
			  permissions, status, created_by_user_id, created_at, updated_at
			) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', '77', '{}'::jsonb, 'ACTIVE', ?, now(), now())
			""".trimIndent(),
			connectionId,
			devContext.devWorkspaceId,
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces (
			  id, workspace_id, provider, namespace_kind, external_namespace_key,
			  status, created_at, updated_at
			) values (?, ?, 'GITHUB', 'GITHUB_ACCOUNT', ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			devContext.devWorkspaceId,
			"shutdown-$key",
		)
		jdbcTemplate.update(
			"""
			insert into connection_namespace_bindings (
			  id, workspace_id, provider, connection_id, source_namespace_id,
			  capabilities, status, valid_from, created_at, updated_at
			) values (?, ?, 'GITHUB', ?, ?, '{}'::jsonb, 'ACTIVE', now(), now(), now())
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			connectionId,
			namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes (
			  id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			  external_scope_key, external_key, display_name, status, created_at, updated_at
			) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', '44', 'acme/plot',
			  'acme/plot', 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
		)
		return persistence.activate(
			devContext.devWorkspaceId,
			scopeId,
			Instant.now(),
		).id
	}

	@TestConfiguration(proxyBeanMethods = false)
	class ShutdownTestConfig {
		@Bean
		@Primary
		fun blockingShutdownGitHubClient() = BlockingShutdownGitHubClient()
	}

	private companion object {
		val TEARDOWN_ERROR_SIGNATURES = listOf("SQLSTATE(08006)", "Socket closed", "HikariDataSource has been closed")
	}
}

class BlockingShutdownGitHubClient : GitHubClient {
	val entered = CountDownLatch(1)
	val release = CountDownLatch(1)
	val calls = AtomicInteger()

	override fun listPublishedReleaseTags(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		limit: Int,
	): GitHubTagPage {
		calls.incrementAndGet()
		entered.countDown()
		while (true) {
			try {
				release.await()
				break
			} catch (_: InterruptedException) {
				Thread.interrupted()
			}
		}
		return GitHubTagPage(listOf("v1.0.0"), false)
	}

	override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> =
		error("not used")

	override fun listClosedPullRequests(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		pageCap: Int,
	): List<GitHubPullRequest> = error("not used")
}
