package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RoutineMigrationIntegrationTest {
	@Autowired private lateinit var dataSource: DataSource
	private lateinit var jdbcTemplate: JdbcTemplate
	private lateinit var schema: String

	@BeforeEach
	fun createIsolatedSchema() {
		jdbcTemplate = JdbcTemplate(dataSource)
		schema = "routine_migration_${UUID.randomUUID().toString().replace("-", "")}"
		jdbcTemplate.execute("create schema $schema")
	}

	@AfterEach
	fun dropIsolatedSchema() {
		jdbcTemplate.execute("drop schema $schema cascade")
	}

	@Test
	fun `V22 preserves unprocessed legacy backlog`() {
		migrateTo("19")
		val userId = UUID.randomUUID()
		val workspaceId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val routineId = UUID.randomUUID()
		val processedBlockId = UUID.randomUUID()
		val backlogBlockId = UUID.randomUUID()
		val lastRunAt = Instant.parse("2026-08-09T00:00:00Z")
		insertLegacyFixture(
			userId,
			workspaceId,
			namespaceId,
			scopeId,
			routineId,
			processedBlockId,
			backlogBlockId,
			lastRunAt,
		)

		migrateTo("22")

		val processedSequence = activitySequence(processedBlockId)
		val backlogSequence = activitySequence(backlogBlockId)
		val cursor = jdbcTemplate.queryForObject(
			"select activity_cursor_sequence from $schema.routines where id = ?",
			Long::class.java,
			routineId,
		)
		assertEquals(processedSequence, cursor)
		assertTrue(backlogSequence > cursor!!)
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

	private fun insertLegacyFixture(
		userId: UUID,
		workspaceId: UUID,
		namespaceId: UUID,
		scopeId: UUID,
		routineId: UUID,
		processedBlockId: UUID,
		backlogBlockId: UUID,
		lastRunAt: Instant,
	) {
		val artifactWorkflowRunId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into $schema.users (id, email, display_name, status, created_at, updated_at) values (?, ?, 'User', 'ACTIVE', now(), now())",
			userId,
			"${UUID.randomUUID()}@example.com",
		)
		jdbcTemplate.update(
			"insert into $schema.workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, 'Workspace', ?, ?, 'ACTIVE', now(), now())",
			workspaceId,
			"workspace-${UUID.randomUUID()}",
			userId,
		)
		jdbcTemplate.update(
			"""
			insert into $schema.source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			workspaceId,
			"repository-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into $schema.source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/plot', 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			workspaceId,
			namespaceId,
			"scope-${UUID.randomUUID()}",
		)
		insertLegacyBlock(workspaceId, namespaceId, scopeId, processedBlockId, lastRunAt.minusSeconds(2))
		insertLegacyBlock(workspaceId, namespaceId, scopeId, backlogBlockId, lastRunAt.minusSeconds(1))
		jdbcTemplate.update(
			"""
			insert into $schema.generation_runs (
			 id, workspace_id, source_scope_id, created_by_user_id, idempotency_key, request_fingerprint,
			 status, workflow_version, prompt_version, output_schema_version, budget_version, provider,
			 model_name, budget_snapshot, user_instruction, finished_at, created_at, updated_at
			) values (?, ?, ?, ?, ?, 'legacy-fingerprint', 'READY', 'legacy-v1', 'legacy-v1',
			 'legacy-v1', 'legacy-v1', 'LEGACY', 'legacy-model', '{}'::jsonb, 'Draft updates', ?, ?, ?)
			""".trimIndent(),
			artifactWorkflowRunId,
			workspaceId,
			scopeId,
			userId,
			"legacy-${UUID.randomUUID()}",
			Timestamp.from(lastRunAt),
			Timestamp.from(lastRunAt),
			Timestamp.from(lastRunAt),
		)
		jdbcTemplate.update(
			"""
			insert into $schema.generation_inputs (
			 id, workspace_id, generation_run_id, writing_block_id, order_index, source_provider,
			 source_kind, source_label, snapshot_body, original_url, content_hash, captured_at
			) values (?, ?, ?, ?, 0, 'GITHUB', 'commit', 'Activity', 'Activity',
			 'https://github.com/acme/plot', 'legacy-hash', ?)
			""".trimIndent(),
			UUID.randomUUID(),
			workspaceId,
			artifactWorkflowRunId,
			processedBlockId,
			Timestamp.from(lastRunAt),
		)
		jdbcTemplate.update(
			"""
			insert into $schema.routines
			(id, workspace_id, created_by_user_id, source_scope_id, name, instruction, cadence,
			 enabled, last_run_at, next_run_at, last_generation_run_id, created_at, updated_at)
			values (?, ?, ?, ?, 'Routine', 'Draft updates', 'DAILY', true, ?, ?, ?, now(), now())
			""".trimIndent(),
			routineId,
			workspaceId,
			userId,
			scopeId,
			Timestamp.from(lastRunAt),
			Timestamp.from(lastRunAt.plusSeconds(86_400)),
			artifactWorkflowRunId,
		)
	}

	private fun insertLegacyBlock(
		workspaceId: UUID,
		namespaceId: UUID,
		scopeId: UUID,
		blockId: UUID,
		ingestedAt: Instant,
	) {
		jdbcTemplate.update(
			"""
			insert into $schema.writing_blocks
			(id, workspace_id, source_namespace_id, external_object_key, source_origin, source_kind,
			 title, ingested_at, status, created_at, updated_at)
			values (?, ?, ?, ?, 'integration', 'commit', 'Activity', ?, 'ACTIVE', ?, ?)
			""".trimIndent(),
			blockId,
			workspaceId,
			namespaceId,
			"commit:$blockId",
			Timestamp.from(ingestedAt),
			Timestamp.from(ingestedAt),
			Timestamp.from(ingestedAt),
		)
		jdbcTemplate.update(
			"""
			insert into $schema.writing_block_scopes
			(id, workspace_id, source_namespace_id, writing_block_id, source_scope_id,
			 membership_kind, status, first_seen_at, last_seen_at)
			values (?, ?, ?, ?, ?, 'CONTAINED_IN', 'ACTIVE', ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			workspaceId,
			namespaceId,
			blockId,
			scopeId,
			Timestamp.from(ingestedAt),
			Timestamp.from(ingestedAt),
		)
	}

	private fun activitySequence(blockId: UUID): Long = jdbcTemplate.queryForObject(
		"select activity_sequence from $schema.writing_blocks where id = ?",
		Long::class.java,
		blockId,
	)!!
}
