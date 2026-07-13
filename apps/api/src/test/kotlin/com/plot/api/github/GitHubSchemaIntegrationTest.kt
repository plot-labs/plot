package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class GitHubSchemaIntegrationTest {
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	@BeforeEach
	fun cleanSourceData() {
		listOf(
			"writing_block_relation_observations", "writing_block_relations", "writing_block_fragments",
			"writing_block_scopes", "source_imports", "source_observations",
		).forEach { jdbcTemplate.update("delete from $it where workspace_id = ?", devContext.devWorkspaceId) }
		jdbcTemplate.update(
			"""
			delete from writing_blocks b
			where b.workspace_id = ? and b.source_namespace_id is not null
			  and not exists (select 1 from generation_inputs i where i.workspace_id = b.workspace_id and i.writing_block_id = b.id)
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from source_scopes s where s.workspace_id = ? and not exists (select 1 from generation_runs r where r.workspace_id = s.workspace_id and r.source_scope_id = s.id)",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update(
			"delete from source_namespaces n where n.workspace_id = ? and not exists (select 1 from source_scopes s where s.workspace_id = n.workspace_id and s.source_namespace_id = n.id) and not exists (select 1 from writing_blocks b where b.workspace_id = n.workspace_id and b.source_namespace_id = n.id)",
			devContext.devWorkspaceId,
		)
		listOf("connections", "github_installation_states").forEach {
			jdbcTemplate.update("delete from $it where workspace_id = ?", devContext.devWorkspaceId)
		}
	}

	@Test
	fun allowsMultipleScopesButOnlyOneRunningImportPerScope() {
		val connectionId = insertConnection(1001)
		val namespaceId = insertNamespace("1001")
		val bindingId = insertBinding(connectionId, namespaceId)
		val firstScopeId = insertScope(namespaceId, 2001)
		val secondScopeId = insertScope(namespaceId, 2002)
		assertEquals(2, jdbcTemplate.queryForObject("select count(*) from source_scopes where workspace_id = ?", Int::class.java, devContext.devWorkspaceId))

		insertRunningImport(firstScopeId, bindingId, 0)
		assertFailsWith<DuplicateKeyException> { insertRunningImport(firstScopeId, bindingId, 1) }
		insertRunningImport(secondScopeId, bindingId, 0)
	}

	@Test
	fun membershipRequiresBlockAndScopeToShareNamespace() {
		val connectionId = insertConnection(2001)
		val firstNamespace = insertNamespace("2001")
		val secondNamespace = insertNamespace("2002")
		val bindingId = insertBinding(connectionId, firstNamespace)
		val firstScope = insertScope(firstNamespace, 3001)
		val secondScope = insertScope(secondNamespace, 3002)
		val observationId = insertObservation(firstScope, bindingId, 0)
		val blockId = insertImportedBlock(firstNamespace, "pr-1")
		insertMembership(blockId, firstNamespace, firstScope, observationId)
		assertFailsWith<DataIntegrityViolationException> {
			insertMembership(blockId, firstNamespace, secondScope, observationId)
		}
	}

	private fun insertConnection(installationId: Long): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into connections (id, workspace_id, provider, connection_kind, external_connection_key,
			 status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, installationId.toString(), devContext.devUserId)
	}

	private fun insertNamespace(key: String): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key,
			 status, created_at, updated_at) values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'ACTIVE', now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, key)
	}

	private fun insertBinding(connectionId: UUID, namespaceId: UUID): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into connection_namespace_bindings (id, workspace_id, provider, connection_id,
			 source_namespace_id, status, valid_from, created_at, updated_at)
			values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, connectionId, namespaceId)
	}

	private fun insertScope(namespaceId: UUID, repositoryId: Long): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics,
			 scope_kind, external_scope_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, 'ACTIVE', now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, namespaceId, repositoryId.toString(), "acme/repo-$repositoryId")
	}

	private fun insertObservation(scopeId: UUID, bindingId: UUID, generation: Long): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into source_observations (id, workspace_id, source_scope_id, binding_id, authority_owner,
			 coverage_key, observation_mode, generation, status, started_at, created_at)
			values (?, ?, ?, ?, ?, 'pull_requests', 'PARTIAL', ?, 'RUNNING', now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, scopeId, bindingId, "github:scope:$scopeId", generation)
	}

	private fun insertRunningImport(scopeId: UUID, bindingId: UUID, generation: Long) {
		val observationId = insertObservation(scopeId, bindingId, generation)
		jdbcTemplate.update("""
			insert into source_imports (id, workspace_id, source_scope_id, observation_id, from_instant,
			 to_instant, status, started_at, created_at)
			values (?, ?, ?, ?, ?, ?, 'RUNNING', now(), now())
		""".trimIndent(), UUID.randomUUID(), devContext.devWorkspaceId, scopeId, observationId,
			Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")))
	}

	private fun insertImportedBlock(namespaceId: UUID, externalKey: String): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update("""
			insert into writing_blocks (id, workspace_id, source_namespace_id, external_object_key,
			 source_origin, source_kind, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, ?, ?, 'integration', 'pull_request', now(), 'ACTIVE', ?, now(), now())
		""".trimIndent(), id, devContext.devWorkspaceId, namespaceId, externalKey, devContext.devUserId)
	}

	private fun insertMembership(blockId: UUID, namespaceId: UUID, scopeId: UUID, observationId: UUID) {
		jdbcTemplate.update("""
			insert into writing_block_scopes (id, workspace_id, source_namespace_id, writing_block_id,
			 source_scope_id, membership_kind, status, first_seen_at, last_seen_at, last_observation_id)
			values (?, ?, ?, ?, ?, 'CONTAINED_IN', 'ACTIVE', now(), now(), ?)
		""".trimIndent(), UUID.randomUUID(), devContext.devWorkspaceId, namespaceId, blockId, scopeId, observationId)
	}
}
