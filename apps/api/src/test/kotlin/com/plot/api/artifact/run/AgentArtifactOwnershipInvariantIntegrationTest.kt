package com.plot.api.artifact.run

import com.plot.api.TestcontainersConfiguration
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import org.springframework.core.io.ClassPathResource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentArtifactOwnershipInvariantIntegrationTest {
	@Autowired private lateinit var dataSource: DataSource
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate

	private lateinit var schema: String
	private lateinit var schemaJdbcTemplate: JdbcTemplate

	@BeforeEach
	fun createIsolatedSchema() {
		schema = "ownership_${UUID.randomUUID().toString().replace("-", "")}"
		jdbcTemplate.execute("create schema $schema")
		Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.defaultSchema(schema)
			.locations("classpath:db/migration")
			.group(true)
			.load()
			.migrate()
		schemaJdbcTemplate = JdbcTemplate(SearchPathDataSource(dataSource, schema))
	}

	@AfterEach
	fun dropIsolatedSchema() {
		jdbcTemplate.execute("drop schema $schema cascade")
	}

	@Test
	@Order(1)
	fun `latest migrated schema accepts conforming and legacy release rows`() {
		assertTrue(runAudit().isEmpty())

		val fixture = insertFixture()
		val agentRunId = insertAgent(fixture, "FAILED")
		val artifactRunId = insertArtifact(fixture, agentRunId, "READY")
		insertGeneration(fixture, agentRunId, artifactRunId, "READY", "conforming")
		insertLegacyRelease(fixture)

		val rows = runAudit()
		val currentSchema = schemaJdbcTemplate.queryForObject("select current_schema()", String::class.java)
		assertTrue(rows.isEmpty(), "schema=$schema current=$currentSchema rows=$rows")
	}

	@Test
	@Order(2)
	fun `audit reports schema-permitted ownership violations with stable codes`() {
		val fixture = insertFixture()
		val owner = insertAgent(fixture, "FAILED")
		val otherOwner = insertAgent(fixture, "FAILED")
		val succeededOwner = insertAgent(fixture, "SUCCEEDED")
		val runningOwner = insertAgent(fixture, "RUNNING")
		val missingAgentOwner = insertAgent(fixture, "FAILED")
		val readyArtifact = insertArtifact(fixture, owner, "READY")
		val failedArtifact = insertArtifact(fixture, otherOwner, "FAILED")
		val succeededFailedArtifact = insertArtifact(fixture, succeededOwner, "FAILED")
		val runningFailedArtifact = insertArtifact(fixture, runningOwner, "FAILED")
		val missingAgentArtifact = insertArtifact(fixture, missingAgentOwner, "FAILED")

		insertGeneration(fixture, owner, readyArtifact, "READY", "valid")
		insertGeneration(fixture, null, missingAgentArtifact, "FAILED", "missing-agent")
		insertGeneration(fixture, owner, null, "FAILED", "missing-artifact")
		insertGeneration(fixture, owner, failedArtifact, "FAILED", "owner-chain")
		insertGeneration(fixture, succeededOwner, succeededFailedArtifact, "FAILED", "terminal")
		insertGeneration(fixture, runningOwner, runningFailedArtifact, "FAILED", "transitional")
		insertReleaseMismatch(fixture, succeededOwner, owner, readyArtifact)

		val rows = runAudit()
		assertEquals(
			setOf(
				"GENERATION_AGENT_MISSING",
				"GENERATION_ARTIFACT_MISSING",
				"GENERATION_OWNER_CHAIN_MISMATCH",
				"AGENT_TERMINAL_ARTIFACT_MISMATCH",
				"RELEASE_OWNERSHIP_MISMATCH",
			),
			rows.map { it["violation_code"] as String }.toSet(),
		)
		assertEquals(5, rows.size)
		assertTrue(rows.all { it.keys == setOf("violation_code", "workspace_id", "entity_id", "detail") })
		assertFalse(rows.any { it["entity_id"] == runningOwner })
	}

	@Test
	@Order(3)
	fun `database constraints reject duplicate and cross-workspace ownership`() {
		val fixture = insertFixture()
		val owner = insertAgent(fixture, "FAILED")
		insertArtifact(fixture, owner, "FAILED")
		assertFailsWith<DataIntegrityViolationException> {
			insertArtifact(fixture, owner, "FAILED")
		}

		val otherFixture = insertFixture()
		assertFailsWith<DataIntegrityViolationException> {
			insertGeneration(otherFixture, owner, null, "FAILED", "cross-workspace")
		}
	}

	private fun runAudit(): List<Map<String, Any?>> {
		val sql = ClassPathResource("db/audit/v1/agent_artifact_ownership.sql")
			.inputStream
			.bufferedReader()
			.use { it.readText() }
		return schemaJdbcTemplate.queryForList(sql)
	}

	private fun insertFixture(): Fixture {
		val now = Instant.parse("2026-08-09T00:00:00Z")
		val userId = UUID.randomUUID()
		val workspaceId = UUID.randomUUID()
		val sessionId = UUID.randomUUID()
		schemaJdbcTemplate.update(
			"insert into users (id, email, display_name, status, created_at, updated_at) values (?, ?, 'Audit Test', 'ACTIVE', ?, ?)",
			userId,
			"${UUID.randomUUID()}@example.com",
			Timestamp.from(now),
			Timestamp.from(now),
		)
		schemaJdbcTemplate.update(
			"insert into workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, 'Audit Workspace', ?, ?, 'ACTIVE', ?, ?)",
			workspaceId,
			"audit-${UUID.randomUUID()}",
			userId,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		schemaJdbcTemplate.update(
			"insert into work_sessions (id, workspace_id, title, status, created_by_user_id, created_at, updated_at) values (?, ?, 'Audit session', 'ACTIVE', ?, ?, ?)",
			sessionId,
			workspaceId,
			userId,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		return Fixture(userId, workspaceId, sessionId, now)
	}

	private fun insertAgent(fixture: Fixture, status: String): UUID {
		val id = UUID.randomUUID()
		val now = Timestamp.from(fixture.now)
		schemaJdbcTemplate.update(
			"""
			insert into agent_runs (
			  id, workspace_id, routine_execution_id, routine_id, work_session_id,
			  created_by_user_id, origin, idempotency_key, request_fingerprint,
			  instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot,
			  status, current_step, attempt_count, max_attempts, transition_version,
			  started_at, finished_at, created_at, updated_at, model_call_count, tool_call_count
			) values (?, ?, null, null, ?, ?, 'CHAT', ?, ?, 'Draft a concise update',
			          'prompt-v1', 'tools-v1', '{}'::jsonb, ?, 0, 0, 3, 0, ?, ?, ?, ?, 0, 0)
			""".trimIndent(),
			id,
			fixture.workspaceId,
			fixture.sessionId,
			fixture.userId,
			"agent:$id",
			"fingerprint:$id",
			status,
			now,
			if (status == "SUCCEEDED" || status == "FAILED") now else null,
			now,
			now,
		)
		return id
	}

	private fun insertArtifact(fixture: Fixture, agentRunId: UUID, status: String): UUID {
		val id = UUID.randomUUID()
		val now = Timestamp.from(fixture.now)
		schemaJdbcTemplate.update(
			"""
			insert into artifact_runs (
			  id, workspace_id, agent_run_id, created_by_user_id, idempotency_key,
			  request_fingerprint, status, transition_version, started_at, finished_at,
			  created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
			""".trimIndent(),
			id,
			fixture.workspaceId,
			agentRunId,
			fixture.userId,
			"artifact:$id",
			"fingerprint:$id",
			status,
			now,
			if (status == "READY" || status == "NEEDS_REVIEW" || status == "FAILED") now else null,
			now,
			now,
		)
		return id
	}

	private fun insertGeneration(
		fixture: Fixture,
		agentRunId: UUID?,
		artifactRunId: UUID?,
		status: String,
		key: String,
	): UUID {
		val id = UUID.randomUUID()
		val now = Timestamp.from(fixture.now)
		schemaJdbcTemplate.update(
			"""
			insert into generation_runs (
			  id, workspace_id, agent_run_id, artifact_run_id, source_scope_id,
			  created_by_user_id, idempotency_key, request_fingerprint, status,
			  workflow_version, prompt_version, output_schema_version, budget_version,
			  provider, model_name, budget_snapshot, transition_version,
			  created_at, updated_at, finished_at
			) values (?, ?, ?, ?, null, ?, ?, ?, ?, 'fixed-v1', 'prompt-v1', 'schema-v1',
			          'budget-v1', 'test', 'model', '{}'::jsonb, 0, ?, ?, ?)
			""".trimIndent(),
			id,
			fixture.workspaceId,
			agentRunId,
			artifactRunId,
			fixture.userId,
			"generation:$key:$id",
			"fingerprint:$key:$id",
			status,
			now,
			now,
			if (status == "READY" || status == "NEEDS_REVIEW" || status == "FAILED") now else null,
		)
		return id
	}

	private fun insertLegacyRelease(fixture: Fixture) {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val deliveryId = UUID.randomUUID()
		val now = Timestamp.from(fixture.now)
		schemaJdbcTemplate.update(
			"insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at) values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'repo', 'ACTIVE', ?, ?)",
			namespaceId, fixture.workspaceId, "legacy-namespace:$namespaceId", now, now,
		)
		schemaJdbcTemplate.update(
			"insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, display_name, status, created_at, updated_at) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'repo', 'ACTIVE', ?, ?)",
			scopeId, fixture.workspaceId, namespaceId, "legacy-scope:$scopeId", now, now,
		)
		schemaJdbcTemplate.update(
			"insert into github_webhook_deliveries (id, external_delivery_id, event_type, payload_hash, disposition, received_at) values (?, ?, 'push', repeat('b', 64), 'OBSERVED', ?)",
			deliveryId, "legacy-delivery:$deliveryId", now,
		)
		schemaJdbcTemplate.update(
			"""
			insert into github_release_draft_requests (
			  id, workspace_id, source_scope_id, initial_delivery_id, tag_name,
			  status, created_at, updated_at
			) values (?, ?, ?, ?, 'v0.9.0', 'READY', ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			fixture.workspaceId,
			scopeId,
			deliveryId,
			now,
			now,
		)
	}

	private fun insertReleaseMismatch(
		fixture: Fixture,
		releaseAgentRunId: UUID,
		generationAgentRunId: UUID,
		generationArtifactRunId: UUID,
	) {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val deliveryId = UUID.randomUUID()
		val requestId = UUID.randomUUID()
		val now = Timestamp.from(fixture.now)
		schemaJdbcTemplate.update(
			"insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at) values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'repo', 'ACTIVE', ?, ?)",
			namespaceId, fixture.workspaceId, "namespace:$namespaceId", now, now,
		)
		schemaJdbcTemplate.update(
			"insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, display_name, status, created_at, updated_at) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'repo', 'ACTIVE', ?, ?)",
			scopeId, fixture.workspaceId, namespaceId, "scope:$scopeId", now, now,
		)
		schemaJdbcTemplate.update(
			"insert into github_webhook_deliveries (id, external_delivery_id, event_type, payload_hash, disposition, received_at) values (?, ?, 'push', repeat('a', 64), 'OBSERVED', ?)",
			deliveryId, "delivery:$deliveryId", now,
		)
		val generationId = insertGeneration(fixture, generationAgentRunId, generationArtifactRunId, "FAILED", "release-generation")
		schemaJdbcTemplate.update(
			"""
			insert into github_release_draft_requests (
			  id, workspace_id, source_scope_id, initial_delivery_id, tag_name,
			  status, agent_run_id, generation_run_id, created_at, updated_at
			) values (?, ?, ?, ?, 'v1.0.0', 'GENERATING', ?, ?, ?, ?)
			""".trimIndent(),
			requestId,
			fixture.workspaceId,
			scopeId,
			deliveryId,
			releaseAgentRunId,
			generationId,
			now,
			now,
		)
	}

	private data class Fixture(
		val userId: UUID,
		val workspaceId: UUID,
		val sessionId: UUID,
		val now: Instant,
	)

	private class SearchPathDataSource(
		private val delegate: DataSource,
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
}
