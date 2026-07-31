package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterEach
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
class GitHubRepositoryAccessCheckIntegrationTest {
	@Autowired private lateinit var persistence: GitHubRepositoryAccessCheckPersistence
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	private val createdScopes = mutableListOf<ScopeFixture>()

	@AfterEach
	fun cleanup() {
		createdScopes.asReversed().forEach { fixture ->
			jdbcTemplate.update("delete from github_repository_access_checks where source_scope_id = ?", fixture.scopeId)
			jdbcTemplate.update("delete from source_scopes where id = ?", fixture.scopeId)
			jdbcTemplate.update("delete from connection_namespace_bindings where id = ?", fixture.bindingId)
			jdbcTemplate.update("delete from source_namespaces where id = ?", fixture.namespaceId)
			jdbcTemplate.update("delete from connections where id = ?", fixture.connectionId)
		}
		createdScopes.clear()
	}

	@Test
	fun verifiedAccessRestoresTheSameScopeBindingAndMetadata() {
		val fixture = createScope()
		persistence.queue(
			devContext.devWorkspaceId,
			fixture.connectionId,
			fixture.scopeId,
			GitHubAccessCheckTrigger.RETRY,
			Instant.now(),
		)
		val item = assertNotNull(persistence.claimNext("access-check-worker", Instant.now()))
		val repository = GitHubRepository(
			id = 44,
			owner = "new-owner",
			name = "plot",
			url = "https://github.com/new-owner/plot",
			defaultBranch = "trunk",
		)

		persistence.completeVerified(item, repository, Instant.now())

		assertEquals("VERIFIED", checkStatusForScope(fixture.scopeId))
		assertEquals(null, scopeReason(fixture.scopeId))
		assertEquals("new-owner/plot", jdbcTemplate.queryForObject(
			"select external_key from source_scopes where id = ?", String::class.java, fixture.scopeId,
		))
		assertEquals("ACTIVE", jdbcTemplate.queryForObject(
			"select status from connection_namespace_bindings where id = ?", String::class.java, fixture.bindingId,
		))
		assertEquals("ACTIVE", jdbcTemplate.queryForObject(
			"select status from connections where id = ?", String::class.java, fixture.connectionId,
		))
		assertEquals(
			"trunk",
			jdbcTemplate.queryForObject(
				"select metadata ->> 'defaultBranch' from source_scopes where id = ?",
				String::class.java,
				fixture.scopeId,
			),
		)
	}

	@Test
	fun staleClaimAdvancesVersionAndRejectsLateVerification() {
		val fixture = createScope()
		persistence.queue(
			devContext.devWorkspaceId,
			fixture.connectionId,
			fixture.scopeId,
			GitHubAccessCheckTrigger.LIFECYCLE_EVENT,
			Instant.now(),
		)
		val item = assertNotNull(persistence.claimNext("stale-worker", Instant.now()))
		jdbcTemplate.update(
			"update github_repository_access_checks set claimed_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
			item.check.id,
		)

		assertEquals(1, persistence.recoverStaleClaims(Instant.now(), Duration.ofMinutes(2), 3))
		assertFailsWith<GitHubAccessCheckClaimLostException> {
			persistence.completeVerified(
				item,
				GitHubRepository(44, "acme", "plot", "https://github.com/acme/plot", "main"),
				Instant.now(),
			)
		}
		assertEquals("QUEUED", checkStatus(item.check.id))
		assertEquals("ERROR", status(fixture.scopeId))
	}

	@Test
	fun thirdStaleClaimBecomesTerminalWithoutAnotherAttempt() {
		val fixture = createScope()
		persistence.queue(
			devContext.devWorkspaceId,
			fixture.connectionId,
			fixture.scopeId,
			GitHubAccessCheckTrigger.LIFECYCLE_EVENT,
			Instant.now(),
		)
		val item = assertNotNull(persistence.claimNext("stale-worker", Instant.now()))
		jdbcTemplate.update(
			"update github_repository_access_checks set attempt_count = 3, claimed_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
			item.check.id,
		)

		assertEquals(1, persistence.recoverStaleClaims(Instant.now(), Duration.ofMinutes(2), 3))
		assertEquals("FAILED", checkStatus(item.check.id))
		assertEquals(
			"ACCESS_CHECK_STALE_CLAIM_LIMIT",
			jdbcTemplate.queryForObject(
				"select error_code from github_repository_access_checks where id = ?",
				String::class.java,
				item.check.id,
			),
		)
	}

	private fun createScope(): ScopeFixture {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val suffix = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into connections
			(id, workspace_id, provider, connection_kind, external_connection_key, permissions, status,
			 created_by_user_id, created_at, updated_at)
			values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, '{}'::jsonb, 'ERROR', ?, now(), now())
			""".trimIndent(),
			connectionId, devContext.devWorkspaceId, "77-$suffix", devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'acme/plot', 'ERROR', now(), now())
			""".trimIndent(),
			namespaceId, devContext.devWorkspaceId, "namespace-$suffix",
		)
		jdbcTemplate.update(
			"""
			insert into connection_namespace_bindings
			(id, workspace_id, provider, connection_id, source_namespace_id, capabilities, status,
			 valid_from, valid_to, created_at, updated_at)
			values (?, ?, 'GITHUB', ?, ?, '{}'::jsonb, 'REVOKED', now(), now(), now(), now())
			""".trimIndent(),
			bindingId, devContext.devWorkspaceId, connectionId, namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key,
			 external_key, display_name, url, metadata, status, status_reason, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', '44', 'acme/plot', 'acme/plot',
			 'https://github.com/acme/plot', '{"repositoryId":44,"defaultBranch":"main"}'::jsonb,
			 'ERROR', 'REPOSITORY_TRANSFERRED', now(), now())
			""".trimIndent(),
			scopeId, devContext.devWorkspaceId, namespaceId,
		)
		return ScopeFixture(connectionId, namespaceId, bindingId, scopeId).also(createdScopes::add)
	}

	private fun checkStatus(id: UUID): String = jdbcTemplate.queryForObject(
		"select status from github_repository_access_checks where id = ?", String::class.java, id,
	)!!

	private fun checkStatusForScope(scopeId: UUID): String = jdbcTemplate.queryForObject(
		"select status from github_repository_access_checks where source_scope_id = ?", String::class.java, scopeId,
	)!!

	private fun status(scopeId: UUID): String = jdbcTemplate.queryForObject(
		"select status from source_scopes where id = ?", String::class.java, scopeId,
	)!!

	private fun scopeReason(scopeId: UUID): String? = jdbcTemplate.queryForObject(
		"select status_reason from source_scopes where id = ?", String::class.java, scopeId,
	)
}

private data class ScopeFixture(
	val connectionId: UUID,
	val namespaceId: UUID,
	val bindingId: UUID,
	val scopeId: UUID,
)
