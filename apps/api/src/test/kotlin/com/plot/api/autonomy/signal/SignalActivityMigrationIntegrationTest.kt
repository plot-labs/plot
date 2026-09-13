package com.plot.api.autonomy.signal

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.UuidGenerator
import java.sql.Timestamp
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SignalActivityMigrationIntegrationTest {
	@Autowired private lateinit var jdbc: JdbcTemplate
	@Autowired private lateinit var uuidGenerator: UuidGenerator

	private val workspaces = mutableListOf<UUID>()
	private val now = Instant.parse("2026-09-13T10:00:00Z")

	@AfterEach
	fun cleanup() {
		workspaces.forEach { id ->
			jdbc.update("delete from legacy_activity_provenance where workspace_id = ?", id)
			jdbc.update("delete from signal_evaluations where workspace_id = ?", id)
			jdbc.update("delete from autonomy_signals where workspace_id = ?", id)
			jdbc.update("delete from source_scopes where workspace_id = ?", id)
			jdbc.update("delete from source_namespaces where workspace_id = ?", id)
			jdbc.update("delete from workspaces where id = ?", id)
		}
	}

	private fun createFixture(): Fixture {
		val workspaceId = uuidGenerator.next()
		workspaces.add(workspaceId)
		val namespaceId = uuidGenerator.next()
		val scopeId = uuidGenerator.next()
		jdbc.update(
			"insert into workspaces(id, name, slug, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
			workspaceId, "Workspace $workspaceId", "ws-$workspaceId", Timestamp.from(now), Timestamp.from(now),
		)
		jdbc.update(
			"insert into source_namespaces(id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at) values (?, ?, 'GITHUB', 'ORGANIZATION', 'org', 'Org', 'ACTIVE', ?, ?)",
			namespaceId, workspaceId, Timestamp.from(now), Timestamp.from(now),
		)
		jdbc.update(
			"insert into source_scopes(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, display_name, status, created_at, updated_at) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', 'repo', 'Repo', 'ACTIVE', ?, ?)",
			scopeId, workspaceId, namespaceId, Timestamp.from(now), Timestamp.from(now),
		)
		val signalId = uuidGenerator.next()
		jdbc.update(
			"""
			insert into autonomy_signals(
				id, workspace_id, source_namespace_id, source_scope_id, provider, delivery_key,
				object_key, event_type, schema_version, payload, received_at, available_at, state
			) values (?, ?, ?, ?, 'GITHUB', 'del-1', 'obj-1', 'release', 1, '{}'::jsonb, ?, ?, 'SUCCEEDED')
			""".trimIndent(),
			signalId, workspaceId, namespaceId, scopeId, Timestamp.from(now), Timestamp.from(now),
		)
		return Fixture(workspaceId, namespaceId, scopeId, signalId)
	}

	@Test
	fun `signal evaluation persists decision reason and enforces uniqueness per signal`() {
		val fixture = createFixture()
		val evalId = uuidGenerator.next()
		jdbc.update(
			"""
			insert into signal_evaluations(
				id, workspace_id, signal_id, source_namespace_id, source_scope_id, input_fingerprint,
				outcome, reason, semantic_time, created_at, updated_at
			) values (?, ?, ?, ?, ?, 'fp-1', 'NO_GENERATION', 'Internal chore only', ?, ?, ?)
			""".trimIndent(),
			evalId, fixture.workspaceId, fixture.signalId, fixture.namespaceId, fixture.scopeId,
			Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)

		val count = jdbc.queryForObject(
			"select count(*) from signal_evaluations where workspace_id = ? and signal_id = ?",
			Int::class.java,
			fixture.workspaceId,
			fixture.signalId,
		)
		assertEquals(1, count)

		// Second evaluation for same signal violates unique (workspace_id, signal_id)
		assertFailsWith<DataIntegrityViolationException> {
			jdbc.update(
				"""
				insert into signal_evaluations(
					id, workspace_id, signal_id, source_namespace_id, source_scope_id, input_fingerprint,
					outcome, reason, semantic_time, created_at, updated_at
				) values (?, ?, ?, ?, ?, 'fp-2', 'ADMITTED', 'Release ready', ?, ?, ?)
				""".trimIndent(),
				uuidGenerator.next(), fixture.workspaceId, fixture.signalId, fixture.namespaceId, fixture.scopeId,
				Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
			)
		}
	}

	@Test
	fun `legacy activity provenance stores detached records without fabricated signal link`() {
		val fixture = createFixture()
		val provId = uuidGenerator.next()
		val oppId = uuidGenerator.next()
		jdbc.update(
			"""
			insert into legacy_activity_provenance(
				id, workspace_id, source_scope_id, opportunity_id, goal_id, task_id, agent_run_id, chat_id,
				title, disposition, reason, dismissed, missing_facts, last_error_code,
				has_exact_signal_link, uncertainty_label, semantic_time, created_at
			) values (
				?, ?, ?, ?, null, null, null, null,
				'Legacy work', 'EXCLUDED', 'Historical opportunity without signal link', false, '[]'::jsonb, null,
				false, 'MIGRATED_HISTORICAL_RECORD', ?, ?
			)
			""".trimIndent(),
			provId, fixture.workspaceId, fixture.scopeId, oppId, Timestamp.from(now), Timestamp.from(now),
		)

		val row = jdbc.queryForMap(
			"select title, disposition, has_exact_signal_link, uncertainty_label from legacy_activity_provenance where id = ?",
			provId,
		)
		assertEquals("Legacy work", row["title"])
		assertEquals("EXCLUDED", row["disposition"])
		assertEquals(false, row["has_exact_signal_link"])
		assertEquals("MIGRATED_HISTORICAL_RECORD", row["uncertainty_label"])
	}

	@Test
	fun `cross workspace links fail foreign key checks`() {
		val f1 = createFixture()
		val f2 = createFixture()

		// Attempt to evaluate f2 signal within f1 workspace
		assertFailsWith<DataIntegrityViolationException> {
			jdbc.update(
				"""
				insert into signal_evaluations(
					id, workspace_id, signal_id, source_namespace_id, source_scope_id, input_fingerprint,
					outcome, reason, semantic_time, created_at, updated_at
				) values (?, ?, ?, ?, ?, 'fp-x', 'NO_GENERATION', 'Cross workspace', ?, ?, ?)
				""".trimIndent(),
				uuidGenerator.next(), f1.workspaceId, f2.signalId, f1.namespaceId, f1.scopeId,
				Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
			)
		}
	}

	private data class Fixture(val workspaceId: UUID, val namespaceId: UUID, val scopeId: UUID, val signalId: UUID)
}
