package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.github.GitHubWebhookCommit
import com.plot.api.github.GitHubWebhookService
import com.plot.api.github.ParsedGitHubWebhook
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.routines.poll-delay=PT1H"])
@Transactional
class GitHubChangeRoutineIntegrationTest {
	@Autowired private lateinit var webhookService: GitHubWebhookService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var entityManager: EntityManager

	@BeforeEach
	fun bootstrap() {
		devBootstrapService.bootstrap()
		entityManager.flush()
	}

	@Test
	fun `default branch push queues enabled github change routines once per delivery`() {
		val sourceScopeId = bindRepository()
		val routineId = insertRoutine(sourceScopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val sha = "b".repeat(40)
		val webhook = ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "push",
			eventAction = null,
			installationId = 77,
			repositoryId = 99,
			ref = "refs/heads/main",
			beforeSha = "a".repeat(40),
			afterSha = sha,
			tagName = null,
			refCreated = false,
			refDeleted = false,
			forced = false,
			commits = listOf(GitHubWebhookCommit(
				sha = sha,
				message = "Ship routines\n\nGenerate a draft on push.",
				author = "octocat",
				timestamp = Instant.parse("2026-08-09T00:00:00Z"),
				url = "https://github.com/acme/repo/commit/$sha",
			)),
			payloadHash = "c".repeat(64),
		)

		webhookService.accept(webhook)
		webhookService.accept(webhook)

		assertEquals("QUEUED", jdbcTemplate.queryForObject(
			"select disposition from github_webhook_deliveries where external_delivery_id = ?",
			String::class.java,
			deliveryId,
		))
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from generation_runs where workspace_id = ? and idempotency_key = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			"routine-github:$routineId:$deliveryId",
		))
		assertEquals("QUEUED", jdbcTemplate.queryForObject(
			"select last_run_status from routines where id = ?", String::class.java, routineId,
		))
		assertEquals("Ship routines", jdbcTemplate.queryForObject(
			"select title from writing_blocks where workspace_id = ? and external_object_key = ?",
			String::class.java,
			devContext.devWorkspaceId,
			"commit:$sha",
		))
	}

	@Test
	fun `published release queues only release routines`() {
		val sourceScopeId = bindRepository()
		val routineId = insertRoutine(sourceScopeId, RoutineCadence.ON_GITHUB_RELEASE)
		val deliveryId = "delivery-${UUID.randomUUID()}"

		webhookService.accept(ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "release",
			eventAction = "published",
			installationId = 77,
			repositoryId = 99,
			ref = null,
			beforeSha = null,
			afterSha = null,
			tagName = "v2.0.0",
			refCreated = null,
			refDeleted = null,
			forced = null,
			payloadHash = "d".repeat(64),
		))

		assertEventRun(routineId, deliveryId, "release", "Release v2.0.0 published")
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from github_release_draft_requests where workspace_id = ? and source_scope_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			sourceScopeId,
		))
	}

	@Test
	fun `tag push queues only tag routines`() {
		val sourceScopeId = bindRepository()
		val routineId = insertRoutine(sourceScopeId, RoutineCadence.ON_GIT_TAG)
		val deliveryId = "delivery-${UUID.randomUUID()}"

		webhookService.accept(ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "push",
			eventAction = null,
			installationId = 77,
			repositoryId = 99,
			ref = "refs/tags/v2.0.0",
			beforeSha = "0".repeat(40),
			afterSha = "e".repeat(40),
			tagName = "v2.0.0",
			refCreated = true,
			refDeleted = false,
			forced = false,
			payloadHash = "f".repeat(64),
		))

		assertEventRun(routineId, deliveryId, "tag", "Tag v2.0.0 pushed")
	}

	private fun insertRoutine(sourceScopeId: UUID, cadence: RoutineCadence): UUID = UUID.randomUUID().also { routineId ->
		jdbcTemplate.update(
			"""
			insert into routines (
			 id, workspace_id, created_by_user_id, source_scope_id, name, instruction, cadence,
			 enabled, next_run_at, created_at, updated_at
			) values (?, ?, ?, ?, 'GitHub update', 'Draft this GitHub event', ?, true, now(), now(), now())
			""".trimIndent(),
			routineId,
			devContext.devWorkspaceId,
			devContext.devUserId,
			sourceScopeId,
			cadence.name,
		)
	}

	private fun assertEventRun(routineId: UUID, deliveryId: String, sourceKind: String, title: String) {
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from generation_runs where workspace_id = ? and idempotency_key = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			"routine-github:$routineId:$deliveryId",
		))
		assertEquals(title, jdbcTemplate.queryForObject(
			"select title from writing_blocks where workspace_id = ? and source_kind = ?",
			String::class.java,
			devContext.devWorkspaceId,
			sourceKind,
		))
	}

	private fun bindRepository(): UUID {
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
		return scopeId
	}
}
