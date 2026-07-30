package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class, GitHubWebhookAfterCommitIntegrationTest.Config::class)
@TestPropertySource(properties = [
	"plot.github.release-automation-enabled=true",
])
class GitHubWebhookAfterCommitIntegrationTest {
	@Autowired private lateinit var webhookService: GitHubWebhookService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var dispatcher: CommitVisibilityDispatcher
	@Autowired private lateinit var failingPersistence: AfterCommitFailingPersistence

	@BeforeEach
	fun clearData() {
		devBootstrapService.bootstrap()
		jdbcTemplate.update("delete from github_release_draft_requests where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from github_webhook_deliveries")
		jdbcTemplate.update("delete from source_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_namespaces where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connections where workspace_id = ?", devContext.devWorkspaceId)
		dispatcher.reset()
		failingPersistence.failQueuedMark.set(false)
	}

	@Test
	fun proxyTransactionDispatchesOnceOnlyAfterTheQueuedRequestCommits() {
		bindRepository()

		webhookService.accept(publishedRelease())

		assertEquals(1, dispatcher.dispatches.get())
		assertEquals(1, dispatcher.visibleQueuedRequests.get())
		assertEquals(1, releaseRequestCount())
	}

	@Test
	fun rollbackSuppressesTheAfterCommitDispatcher() {
		bindRepository()
		failingPersistence.failQueuedMark.set(true)

		assertFailsWith<IllegalStateException> { webhookService.accept(publishedRelease()) }

		assertEquals(0, dispatcher.dispatches.get())
		assertEquals(0, releaseRequestCount())
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from github_webhook_deliveries", Int::class.java))
	}

	private fun bindRepository() {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at)
				values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', '77', 'ACTIVE', ?, now(), now())""".trimIndent(),
			connectionId, devContext.devWorkspaceId, devContext.devUserId,
		)
		jdbcTemplate.update(
			"""insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
				values (?, ?, 'GITHUB', 'REPOSITORY', 'repository:99', 'ACTIVE', now(), now())""".trimIndent(),
			namespaceId, devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"""insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at)
				values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())""".trimIndent(),
			bindingId, devContext.devWorkspaceId, connectionId, namespaceId,
		)
		jdbcTemplate.update(
			"""insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, external_key, display_name, metadata, status, created_at, updated_at)
				values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', '99', 'acme/repo', 'acme/repo', '{"defaultBranch":"main"}'::jsonb, 'ACTIVE', now(), now())""".trimIndent(),
			scopeId, devContext.devWorkspaceId, namespaceId,
		)
	}

	private fun publishedRelease() = ParsedGitHubWebhook(
		externalDeliveryId = "after-commit-${UUID.randomUUID()}",
		eventType = "release",
		eventAction = "published",
		installationId = 77,
		repositoryId = 99,
		ref = null,
		beforeSha = null,
		afterSha = null,
		tagName = "v1.2.0",
		refCreated = null,
		refDeleted = null,
		forced = null,
		payloadHash = "a".repeat(64),
	)

	private fun releaseRequestCount(): Int = jdbcTemplate.queryForObject(
		"select count(*) from github_release_draft_requests", Int::class.java,
	)!!

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun commitVisibilityDispatcher(
			jdbcTemplate: JdbcTemplate,
			transactionManager: PlatformTransactionManager,
		) = CommitVisibilityDispatcher(jdbcTemplate, transactionManager)

		@Bean
		@Primary
		fun failingReleasePersistence(delegate: JdbcGitHubReleasePersistence) = AfterCommitFailingPersistence(delegate)
	}
}

class CommitVisibilityDispatcher(
	private val jdbcTemplate: JdbcTemplate,
	transactionManager: PlatformTransactionManager,
) : GitHubReleaseDraftDispatcher {
	private val requiresNew = TransactionTemplate(transactionManager).apply {
		propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
	}
	val dispatches = AtomicInteger()
	val visibleQueuedRequests = AtomicInteger()

	override fun dispatch() {
		dispatches.incrementAndGet()
		visibleQueuedRequests.set(requiresNew.execute {
			jdbcTemplate.queryForObject("select count(*) from github_release_draft_requests", Int::class.java)
		} ?: 0)
	}

	fun reset() {
		dispatches.set(0)
		visibleQueuedRequests.set(0)
	}
}

class AfterCommitFailingPersistence(
	private val delegate: GitHubReleasePersistence,
) : GitHubReleasePersistence by delegate {
	val failQueuedMark = AtomicBoolean()

	override fun markDelivery(id: UUID, disposition: GitHubWebhookDisposition, errorCode: String?) {
		if (disposition == GitHubWebhookDisposition.QUEUED && failQueuedMark.compareAndSet(true, false)) {
			throw IllegalStateException("planned queued-delivery failure")
		}
		delegate.markDelivery(id, disposition, errorCode)
	}
}
