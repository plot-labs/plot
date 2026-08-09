package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.UuidGenerator
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RoutineCutoverMigrationIntegrationTest {
	@Autowired private lateinit var dataSource: javax.sql.DataSource
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var uuidGenerator: UuidGenerator

	private lateinit var schema: String
	private lateinit var schemaDataSource: SearchPathDataSource
	private lateinit var schemaJdbcTemplate: JdbcTemplate

	@BeforeEach
	fun createV23Schema() {
		schema = "routine_cutover_${UUID.randomUUID().toString().replace("-", "")}"
		jdbcTemplate.execute("create schema $schema")
		migrate(MigrationVersion.fromVersion("23"))
		schemaDataSource = SearchPathDataSource(dataSource, schema)
		schemaJdbcTemplate = JdbcTemplate(schemaDataSource)
	}

	@AfterEach
	fun dropSchema() {
		jdbcTemplate.execute("drop schema $schema cascade")
	}

	@Test
	fun `V24 retires legacy GitHub events into terminal canonical executions without Chats`() {
		val fixture = insertFixture()
		insertLegacyEvent(fixture, fixture.succeededEventId, fixture.succeededDeliveryId, "SUCCEEDED", fixture.generationRunId, null)
		insertLegacyEvidence(fixture, fixture.succeededEventId, orderIndex = 0, activitySequence = 42)
		insertLegacyEvent(fixture, fixture.queuedEventId, fixture.queuedDeliveryId, "QUEUED", null, null)
		insertLegacyEvidence(fixture, fixture.queuedEventId, orderIndex = 0, activitySequence = 43)

		migrate(null)

		assertEquals(1, count("routines"))
		assertEquals(1, count("generation_runs"))
		assertEquals(2, count("routine_github_event_runs"))
		assertEquals(2, count("routine_executions"))
		assertEquals(0, count("work_sessions"))
		assertEquals(0, count("agent_runs"))

		val succeeded = canonical(fixture.succeededEventId)
		assertEquals(RoutineExecutionStatus.FAILED, succeeded.status)
		assertEquals("LEGACY_DIRECT_GENERATION", succeeded.errorCode)
		assertEquals(fixture.generationRunId, succeeded.legacyGenerationRunId)
		assertEquals("github:${fixture.routineId}:${fixture.succeededDeliveryId}", succeeded.triggerKey)

		val queued = canonical(fixture.queuedEventId)
		assertEquals(RoutineExecutionStatus.FAILED, queued.status)
		assertEquals("LEGACY_EVENT_CUTOVER", queued.errorCode)
		assertNull(queued.legacyGenerationRunId)
		assertEquals(42L, evidenceSequence(fixture.succeededEventId))
		assertEquals(43L, evidenceSequence(fixture.queuedEventId))
		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from $schema.routine_execution_evidence where legacy_event_run_id is not null",
			Int::class.java,
		))

		assertNotNull(schemaJdbcTemplate.queryForObject(
			"select retired_at from $schema.routine_github_event_runs where id = ?",
			Instant::class.java,
			fixture.succeededEventId,
		))
		assertEquals("CANONICAL_EXECUTION_CUTOVER", schemaJdbcTemplate.queryForObject(
			"select retirement_code from $schema.routine_github_event_runs where id = ?",
			String::class.java,
			fixture.succeededEventId,
		))
		assertNull(schemaJdbcTemplate.queryForObject(
			"select agent_run_id from $schema.generation_runs where id = ?",
			UUID::class.java,
			fixture.generationRunId,
		))
		assertNull(schemaJdbcTemplate.queryForObject(
			"select active_execution_id from $schema.routines where id = ?",
			UUID::class.java,
			fixture.routineId,
		))
		assertNull(schemaJdbcTemplate.queryForObject(
			"select claimed_by from $schema.routines where id = ?",
			String::class.java,
			fixture.routineId,
		))

		val legacyPersistence = GitHubRoutineEventPersistence(
			schemaJdbcTemplate,
			TransactionTemplate(DataSourceTransactionManager(schemaDataSource)),
			uuidGenerator,
			Clock.fixed(TEST_NOW, java.time.ZoneOffset.UTC),
		)
		assertNull(legacyPersistence.claimNext("legacy-worker", TEST_NOW, Duration.ofMinutes(2)))
	}

	private fun insertFixture(): Fixture {
		val userId = UUID.randomUUID()
		val workspaceId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val routineId = UUID.randomUUID()
		val blockId = UUID.randomUUID()
		val generationRunId = UUID.randomUUID()
		val succeededEventId = UUID.randomUUID()
		val succeededDeliveryId = UUID.randomUUID()
		val queuedEventId = UUID.randomUUID()
		val queuedDeliveryId = UUID.randomUUID()
		schemaJdbcTemplate.update(
			"insert into $schema.users (id, email, display_name, status, created_at, updated_at) values (?, ?, 'Cutover Test', 'ACTIVE', ?, ?)",
			userId, "${UUID.randomUUID()}@example.com", Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW),
		)
		schemaJdbcTemplate.update(
			"insert into $schema.workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, 'Cutover Workspace', ?, ?, 'ACTIVE', ?, ?)",
			workspaceId, "cutover-${UUID.randomUUID()}", userId, Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW),
		)
		schemaJdbcTemplate.update(
			"insert into $schema.source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at) values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'Acme', 'ACTIVE', ?, ?)",
			namespaceId, workspaceId, "namespace:$namespaceId", Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW),
		)
		schemaJdbcTemplate.update(
			"insert into $schema.source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, display_name, status, status_changed_at, created_at, updated_at) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/plot', 'ACTIVE', ?, ?, ?)",
			scopeId, workspaceId, namespaceId, "scope:$scopeId", Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW),
		)
		schemaJdbcTemplate.update(
			"insert into $schema.writing_blocks (id, workspace_id, source_namespace_id, external_object_key, source_origin, source_kind, title, body, url, canonical_url, platform, content_hash, source_created_at, source_updated_at, ingested_at, status, created_by_user_id, created_at, updated_at) values (?, ?, ?, 'commit:legacy', 'integration', 'commit', 'Legacy activity', 'Legacy body', 'https://github.com/acme/plot/commit/legacy', 'https://github.com/acme/plot/commit/legacy', 'github', 'legacy-hash', ?, ?, ?, 'ACTIVE', ?, ?, ?)",
			blockId, workspaceId, namespaceId, Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW), userId, Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW),
		)
		schemaJdbcTemplate.update(
			"insert into $schema.writing_block_scopes (id, workspace_id, source_namespace_id, writing_block_id, source_scope_id, membership_kind, status, first_seen_at, last_seen_at) values (?, ?, ?, ?, ?, 'CONTAINED_IN', 'ACTIVE', ?, ?)",
			UUID.randomUUID(), workspaceId, namespaceId, blockId, scopeId, Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW),
		)
		schemaJdbcTemplate.update(
			"insert into $schema.routines (id, workspace_id, created_by_user_id, source_scope_id, name, instruction, cadence, enabled, next_run_at, created_at, updated_at, claimed_by, claimed_at, active_execution_id) values (?, ?, ?, ?, 'Legacy routine', 'Draft legacy', 'ON_GITHUB_CHANGE', true, ?, ?, ?, ?, ?, ?)",
			routineId, workspaceId, userId, scopeId, Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW), "routine-github:$succeededEventId", Timestamp.from(TEST_NOW), succeededEventId,
		)
		schemaJdbcTemplate.update(
			"insert into $schema.github_webhook_deliveries (id, external_delivery_id, event_type, payload_hash, disposition, received_at) values (?, ?, 'push', ?, 'QUEUED', ?)",
			succeededDeliveryId, "delivery-$succeededDeliveryId", "a".repeat(64), Timestamp.from(TEST_NOW),
		)
		schemaJdbcTemplate.update(
			"insert into $schema.github_webhook_deliveries (id, external_delivery_id, event_type, payload_hash, disposition, received_at) values (?, ?, 'push', ?, 'QUEUED', ?)",
			queuedDeliveryId, "delivery-$queuedDeliveryId", "b".repeat(64), Timestamp.from(TEST_NOW.plusSeconds(1)),
		)
		schemaJdbcTemplate.update(
			"insert into $schema.generation_runs (id, workspace_id, source_scope_id, created_by_user_id, idempotency_key, request_fingerprint, status, workflow_version, prompt_version, output_schema_version, budget_version, provider, model_name, budget_snapshot, created_at, updated_at, finished_at) values (?, ?, ?, ?, 'legacy-generation', 'legacy-fingerprint', 'READY', 'fixed-v1', 'prompt-v1', 'schema-v1', 'budget-v1', 'test', 'model', '{}'::jsonb, ?, ?, ?)",
			generationRunId, workspaceId, scopeId, userId, Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW), Timestamp.from(TEST_NOW),
		)
		return Fixture(
			workspaceId, routineId, blockId, generationRunId,
			succeededEventId, succeededDeliveryId, queuedEventId, queuedDeliveryId,
		)
	}

	private fun insertLegacyEvent(
		fixture: Fixture,
		eventId: UUID,
		deliveryId: UUID,
		status: String,
		generationRunId: UUID?,
		claimedBy: String?,
	) {
		schemaJdbcTemplate.update(
			"insert into $schema.routine_github_event_runs (id, workspace_id, routine_id, delivery_id, status, attempt_count, transition_version, generation_run_id, error_code, claimed_by, claimed_at, created_at, updated_at, finished_at) values (?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?, ?, ?, ?)",
			eventId, fixture.workspaceId, fixture.routineId, deliveryId, status, generationRunId,
			if (status == "FAILED") "LEGACY_FAILURE" else null,
			claimedBy,
			if (claimedBy == null) null else Timestamp.from(TEST_NOW),
			Timestamp.from(TEST_NOW),
			Timestamp.from(TEST_NOW),
			if (status == "QUEUED") null else Timestamp.from(TEST_NOW),
		)
	}

	private fun insertLegacyEvidence(fixture: Fixture, eventId: UUID, orderIndex: Int, activitySequence: Long) {
		schemaJdbcTemplate.update(
			"insert into $schema.routine_github_event_evidence (event_run_id, workspace_id, writing_block_id, writing_block_activity_sequence, order_index) values (?, ?, ?, ?, ?)",
			eventId, fixture.workspaceId, fixture.blockId, activitySequence, orderIndex,
		)
	}

	private fun canonical(id: UUID): LegacyCanonicalExecution = requireNotNull(
		schemaJdbcTemplate.query(
			"""
			select status, error_code, legacy_generation_run_id, trigger_key
			from $schema.routine_executions
			where id = ?
			""".trimIndent(),
			{ rs, _ ->
				LegacyCanonicalExecution(
					status = RoutineExecutionStatus.valueOf(rs.getString("status")),
					errorCode = rs.getString("error_code"),
					legacyGenerationRunId = rs.getObject("legacy_generation_run_id", UUID::class.java),
					triggerKey = rs.getString("trigger_key"),
				)
			},
			id,
		).firstOrNull(),
	) { "canonical execution $id is missing" }

	private fun evidenceSequence(executionId: UUID): Long = schemaJdbcTemplate.queryForObject(
		"select activity_sequence from $schema.routine_execution_evidence where execution_id = ?",
		Long::class.java,
		executionId,
	)!!

	private fun count(table: String): Int = schemaJdbcTemplate.queryForObject(
		"select count(*) from $schema.$table",
		Int::class.java,
	) ?: 0

	private fun migrate(target: MigrationVersion?) {
		val config = Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.defaultSchema(schema)
			.locations("classpath:db/migration")
			.group(true)
		if (target != null) config.target(target)
		config.load().migrate()
	}

	private data class Fixture(
		val workspaceId: UUID,
		val routineId: UUID,
		val blockId: UUID,
		val generationRunId: UUID,
		val succeededEventId: UUID,
		val succeededDeliveryId: UUID,
		val queuedEventId: UUID,
		val queuedDeliveryId: UUID,
	)

	private data class LegacyCanonicalExecution(
		val status: RoutineExecutionStatus,
		val errorCode: String?,
		val legacyGenerationRunId: UUID?,
		val triggerKey: String,
	)

	private class SearchPathDataSource(
		private val delegate: javax.sql.DataSource,
		private val schema: String,
	) : AbstractDataSource() {
		override fun getConnection(): Connection = delegate.connection.withSchema()
		override fun getConnection(username: String?, password: String?): Connection =
			delegate.getConnection(username, password).withSchema()
		private fun Connection.withSchema(): Connection = apply {
			setSchema(schema)
			createStatement().use { it.execute("set search_path to '$schema'") }
		}
	}

	private companion object {
		val TEST_NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
	}
}
