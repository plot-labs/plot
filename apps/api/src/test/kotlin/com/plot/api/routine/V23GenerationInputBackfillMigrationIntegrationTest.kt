package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class V23GenerationInputBackfillMigrationIntegrationTest {

	@Autowired private lateinit var dataSource: DataSource
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate

	private lateinit var schema: String

	@BeforeEach
	fun createIsolatedSchema() {
		schema = "v23_backfill_${UUID.randomUUID().toString().replace("-", "")}"
		jdbcTemplate.execute("create schema $schema")
	}

	@AfterEach
	fun dropIsolatedSchema() {
		jdbcTemplate.execute("drop schema $schema cascade")
	}

	@Test
	fun `V23 backfills legacy inputs without weakening their append-only guard`() {
		migrateTo("22")
		val fixture = insertLegacyGenerationInput()

		migrateTo("23")

		assertEquals(
			fixture.sourceScopeId,
			jdbcTemplate.queryForObject(
				"select source_scope_id from $schema.generation_inputs where id = ?",
				UUID::class.java,
				fixture.generationInputId,
			),
		)
		assertFailsWith<DataIntegrityViolationException> {
			jdbcTemplate.update(
				"update $schema.generation_inputs set source_scope_id = null where id = ?",
				fixture.generationInputId,
			)
		}
	}

	private fun migrateTo(version: String) {
		Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.defaultSchema(schema)
			.locations("classpath:db/migration")
			.target(MigrationVersion.fromVersion(version))
			.group(true)
			.load()
			.migrate()
	}

	private fun insertLegacyGenerationInput(): LegacyInputFixture {
		val now = Timestamp.from(Instant.parse("2026-08-19T00:00:00Z"))
		val userId = UUID.randomUUID()
		val workspaceId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val sourceScopeId = UUID.randomUUID()
		val writingBlockId = UUID.randomUUID()
		val generationRunId = UUID.randomUUID()
		val generationInputId = UUID.randomUUID()

		jdbcTemplate.update(
			"insert into $schema.users (id, email, display_name, status, created_at, updated_at) values (?, ?, 'Migration Test', 'ACTIVE', ?, ?)",
			userId,
			"${UUID.randomUUID()}@example.com",
			now,
			now,
		)
		jdbcTemplate.update(
			"insert into $schema.workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, 'Migration Workspace', ?, ?, 'ACTIVE', ?, ?)",
			workspaceId,
			"migration-${UUID.randomUUID()}",
			userId,
			now,
			now,
		)
		jdbcTemplate.update(
			"insert into $schema.source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at) values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'Migration repository', 'ACTIVE', ?, ?)",
			namespaceId,
			workspaceId,
			"namespace:$namespaceId",
			now,
			now,
		)
		jdbcTemplate.update(
			"insert into $schema.source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, display_name, status, status_changed_at, created_at, updated_at) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'Migration repository', 'ACTIVE', ?, ?, ?)",
			sourceScopeId,
			workspaceId,
			namespaceId,
			"scope:$sourceScopeId",
			now,
			now,
			now,
		)
		jdbcTemplate.update(
			"insert into $schema.writing_blocks (id, workspace_id, source_namespace_id, external_object_key, source_origin, source_kind, title, body, url, canonical_url, platform, content_hash, ingested_at, status, created_by_user_id, created_at, updated_at) values (?, ?, ?, ?, 'migration', 'commit', 'Migration input', 'Legacy input', ?, ?, 'github', 'legacy-input', ?, 'ACTIVE', ?, ?, ?)",
			writingBlockId,
			workspaceId,
			namespaceId,
			"commit:$writingBlockId",
			"https://github.com/acme/plot/commit/$writingBlockId",
			"https://github.com/acme/plot/commit/$writingBlockId",
			now,
			userId,
			now,
			now,
		)
		jdbcTemplate.update(
			"insert into $schema.generation_runs (id, workspace_id, source_scope_id, created_by_user_id, idempotency_key, request_fingerprint, status, workflow_version, prompt_version, output_schema_version, budget_version, provider, model_name, budget_snapshot, created_at, updated_at, finished_at) values (?, ?, ?, ?, 'migration-run', 'migration-fingerprint', 'READY', 'workflow-v1', 'prompt-v1', 'schema-v1', 'budget-v1', 'test', 'test-model', '{}'::jsonb, ?, ?, ?)",
			generationRunId,
			workspaceId,
			sourceScopeId,
			userId,
			now,
			now,
			now,
		)
		jdbcTemplate.update(
			"insert into $schema.generation_inputs (id, workspace_id, generation_run_id, writing_block_id, order_index, source_provider, source_kind, source_label, snapshot_title, snapshot_body, snapshot_excerpt, original_url, source_created_at, source_updated_at, content_hash, captured_at) values (?, ?, ?, ?, 0, 'GITHUB', 'COMMIT', 'Migration input', 'Migration input', 'Legacy input', 'Legacy input', 'https://github.com/acme/plot/commit/legacy', ?, ?, 'legacy-input', ?)",
			generationInputId,
			workspaceId,
			generationRunId,
			writingBlockId,
			now,
			now,
			now,
		)

		return LegacyInputFixture(generationInputId, sourceScopeId)
	}

	private data class LegacyInputFixture(
		val generationInputId: UUID,
		val sourceScopeId: UUID,
	)
}
