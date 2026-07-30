package com.plot.api.writingblock

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.dev.DevContext
import com.plot.api.source.ImportedWritingBlock
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class WritingBlockApiIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var importService: WritingBlockImportService

	@BeforeEach
	fun cleanDevWritingBlockData() {
		jdbcTemplate.update("delete from writing_block_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from writing_blocks where workspace_id = ?", devContext.devWorkspaceId)
	}

	@Test
	fun listReturnsWritingBlocksByCreatedAtDescending() {
		val olderId = UUID.randomUUID()
		val newerId = UUID.randomUUID()
		insertWritingBlock(olderId, "Older Block", Instant.parse("2026-01-01T00:00:00Z"))
		insertWritingBlock(newerId, "Newer Block", Instant.parse("2026-01-02T00:00:00Z"))

		mockMvc.get("/api/blocks")
			.andExpect {
				status { isOk() }
				jsonPath("$.items[0].id") { value(newerId.toString()) }
				jsonPath("$.items[1].id") { value(olderId.toString()) }
				jsonPath("$.items[0].workspaceId") { doesNotExist() }
				jsonPath("$.items[0].contentHash") { doesNotExist() }
			}
	}

	@Test
	fun `manual import keeps request context while explicit import uses only supplied principal`() {
		val manualFixture = sourceFixture(WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId))
		val backgroundPrincipal = createPrincipal()
		val backgroundFixture = sourceFixture(backgroundPrincipal)
		val now = Instant.parse("2026-07-30T00:00:00Z")

		val manual = importService.upsert(
			importedBlock(manualFixture, "manual-pr", "Manual request context"),
			now,
		)
		val background = importService.upsert(
			backgroundPrincipal,
			importedBlock(backgroundFixture, "background-pr", "Background supplied context"),
			now,
		)

		assertWorkspace(manual.blockId, devContext.devWorkspaceId, devContext.devUserId)
		assertWorkspace(background.blockId, backgroundPrincipal.workspaceId, backgroundPrincipal.userId)
		kotlin.test.assertEquals(backgroundPrincipal.workspaceId, jdbcTemplate.queryForObject(
			"select workspace_id from writing_block_scopes where writing_block_id = ?",
			UUID::class.java,
			background.blockId,
		))
	}

	private fun insertWritingBlock(id: UUID, title: String, createdAt: Instant) {
		val timestamp = Timestamp.from(createdAt)
		jdbcTemplate.update(
			"""
			insert into writing_blocks (
				id, workspace_id, source_origin, source_kind, title, body,
				content_hash, ingested_at, status, created_by_user_id, created_at, updated_at
			)
			values (?, ?, 'manual', 'note', ?, 'Body', 'hash', ?, 'ACTIVE', ?, ?, ?)
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			title,
			timestamp,
			devContext.devUserId,
			timestamp,
			timestamp,
		)
	}

	private fun sourceFixture(principal: WorkspacePrincipal): ImportFixture {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val observationId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'Acme', 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			principal.workspaceId,
			"installation-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/plot', 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			principal.workspaceId,
			namespaceId,
			"repository-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into source_observations
			(id, workspace_id, source_scope_id, authority_owner, coverage_key, observation_mode,
			 generation, status, started_at, completed_at, created_at)
			values (?, ?, ?, 'TEST', ?, 'PARTIAL', 0, 'COMPLETED', now(), now(), now())
			""".trimIndent(),
			observationId,
			principal.workspaceId,
			scopeId,
			"test:${UUID.randomUUID()}",
		)
		return ImportFixture(namespaceId, scopeId, observationId)
	}

	private fun createPrincipal(): WorkspacePrincipal {
		val principal = WorkspacePrincipal(UUID.randomUUID(), UUID.randomUUID())
		jdbcTemplate.update(
			"insert into users (id, email, display_name, status, created_at, updated_at) values (?, ?, 'Worker', 'ACTIVE', now(), now())",
			principal.userId,
			"worker-${principal.userId}@example.test",
		)
		jdbcTemplate.update(
			"insert into workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, 'Worker', ?, ?, 'ACTIVE', now(), now())",
			principal.workspaceId,
			"worker-${principal.workspaceId}",
			principal.userId,
		)
		return principal
	}

	private fun importedBlock(fixture: ImportFixture, key: String, title: String) = ImportedWritingBlock(
		sourceNamespaceId = fixture.namespaceId,
		sourceScopeId = fixture.scopeId,
		observationId = fixture.observationId,
		externalObjectKey = key,
		sourceOrigin = "integration",
		sourceKind = "pull_request",
		title = title,
		body = "Body",
		url = "https://github.test/acme/plot/pull/1",
		canonicalUrl = "https://github.test/acme/plot/pull/1",
		author = "ada",
		platform = "github",
		metadata = emptyMap(),
		sourceCreatedAt = Instant.parse("2026-07-29T00:00:00Z"),
		sourceUpdatedAt = Instant.parse("2026-07-29T00:00:00Z"),
	)

	private fun assertWorkspace(blockId: UUID, workspaceId: UUID, userId: UUID) {
		kotlin.test.assertEquals(workspaceId, jdbcTemplate.queryForObject(
			"select workspace_id from writing_blocks where id = ?",
			UUID::class.java,
			blockId,
		))
		kotlin.test.assertEquals(userId, jdbcTemplate.queryForObject(
			"select created_by_user_id from writing_blocks where id = ?",
			UUID::class.java,
			blockId,
		))
	}
}

private data class ImportFixture(
	val namespaceId: UUID,
	val scopeId: UUID,
	val observationId: UUID,
)
