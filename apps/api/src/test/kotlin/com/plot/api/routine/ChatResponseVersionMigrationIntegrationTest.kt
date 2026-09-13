package com.plot.api.routine

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
class ChatResponseVersionMigrationIntegrationTest {
	@Autowired private lateinit var jdbc: JdbcTemplate
	@Autowired private lateinit var uuidGenerator: UuidGenerator

	private val workspaces = mutableListOf<UUID>()
	private val now = Instant.parse("2026-09-13T10:00:00Z")

	@AfterEach
	fun cleanup() {
		workspaces.forEach { id ->
			jdbc.update("delete from chat_execution_transcript_entries where workspace_id = ?", id)
			jdbc.update("delete from chat_execution_envelopes where workspace_id = ?", id)
			jdbc.update("delete from signal_evaluations where workspace_id = ?", id)
			jdbc.update("delete from chat_response_versions where workspace_id = ?", id)
			jdbc.update("delete from chat_turns where workspace_id = ?", id)
			jdbc.update("delete from agent_run_inputs where workspace_id = ?", id)
			jdbc.update("delete from agent_run_sources where workspace_id = ?", id)
			jdbc.update("delete from agent_runs where workspace_id = ?", id)
			jdbc.update("delete from content_source_snapshots where workspace_id = ?", id)
			jdbc.update("delete from work_sessions where workspace_id = ?", id)
			jdbc.update("delete from source_scopes where workspace_id = ?", id)
			jdbc.update("delete from source_namespaces where workspace_id = ?", id)
			jdbc.update("delete from workspaces where id = ?", id)
		}
	}

	private fun createFixture(): Fixture {
		val workspaceId = uuidGenerator.next()
		workspaces.add(workspaceId)
		val userId = uuidGenerator.next()
		jdbc.update(
			"insert into workspaces(id, name, slug, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', ?, ?)",
			workspaceId, "Workspace $workspaceId", "ws-$workspaceId", Timestamp.from(now), Timestamp.from(now),
		)
		jdbc.update(
			"insert into users(id, email, display_name, status, created_at, updated_at) values (?, ?, 'User', 'ACTIVE', ?, ?) on conflict do nothing",
			userId, "user-$userId@example.com", Timestamp.from(now), Timestamp.from(now),
		)
		val sessionId = uuidGenerator.next()
		jdbc.update(
			"insert into work_sessions(id, workspace_id, title, status, created_by_user_id, created_at, updated_at) values (?, ?, 'Chat', 'OPEN', ?, ?, ?)",
			sessionId, workspaceId, userId, Timestamp.from(now), Timestamp.from(now),
		)
		return Fixture(workspaceId, userId, sessionId)
	}

	private fun insertAgentRun(workspaceId: UUID, sessionId: UUID, userId: UUID, key: String): UUID {
		val runId = uuidGenerator.next()
		jdbc.update(
			"""
			insert into agent_runs(
				id, workspace_id, work_session_id, created_by_user_id, origin, idempotency_key,
				request_fingerprint, instruction_snapshot, prompt_version, tool_policy_version,
				budget_snapshot, status, created_at, updated_at
			) values (?, ?, ?, ?, 'CHAT', ?, ?, 'inst', 'v1', 'v1', '{}'::jsonb, 'SUCCEEDED', ?, ?)
			""".trimIndent(),
			runId, workspaceId, sessionId, userId, key, "fp-$key", Timestamp.from(now), Timestamp.from(now),
		)
		return runId
	}

	@Test
	fun `chat turns and response versions maintain ordered versions for a turn`() {
		val fixture = createFixture()
		val turnId = uuidGenerator.next()
		jdbc.update(
			"""
			insert into chat_turns(id, workspace_id, work_session_id, turn_index, user_message, created_by_user_id, created_at, updated_at)
			values (?, ?, ?, 0, 'Hello world', ?, ?, ?)
			""".trimIndent(),
			turnId, fixture.workspaceId, fixture.sessionId, fixture.userId, Timestamp.from(now), Timestamp.from(now),
		)

		val runId1 = insertAgentRun(fixture.workspaceId, fixture.sessionId, fixture.userId, "key-1")
		val versionId1 = uuidGenerator.next()
		jdbc.update(
			"""
			insert into chat_response_versions(
				id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, lineage_parent_version_id, created_at, updated_at
			) values (?, ?, ?, 0, ?, ?, null, ?, ?)
			""".trimIndent(),
			versionId1, fixture.workspaceId, turnId, runId1, fixture.userId, Timestamp.from(now), Timestamp.from(now),
		)

		val runId2 = insertAgentRun(fixture.workspaceId, fixture.sessionId, fixture.userId, "key-2")
		val versionId2 = uuidGenerator.next()
		jdbc.update(
			"""
			insert into chat_response_versions(
				id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, lineage_parent_version_id, created_at, updated_at
			) values (?, ?, ?, 1, ?, ?, ?, ?, ?)
			""".trimIndent(),
			versionId2, fixture.workspaceId, turnId, runId2, fixture.userId, versionId1, Timestamp.from(now), Timestamp.from(now),
		)

		val count = jdbc.queryForObject(
			"select count(*) from chat_response_versions where workspace_id = ? and turn_id = ?",
			Int::class.java,
			fixture.workspaceId,
			turnId,
		)
		assertEquals(2, count)

		// Duplicate version_index for the same turn should fail
		assertFailsWith<DataIntegrityViolationException> {
			jdbc.update(
				"""
				insert into chat_response_versions(
					id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, created_at, updated_at
				) values (?, ?, ?, 1, ?, ?, ?, ?)
				""".trimIndent(),
				uuidGenerator.next(), fixture.workspaceId, turnId, runId1, fixture.userId, Timestamp.from(now), Timestamp.from(now),
			)
		}
	}

	@Test
	fun `one to one agent run to response version invariant is enforced`() {
		val fixture = createFixture()
		val turnId = uuidGenerator.next()
		jdbc.update(
			"insert into chat_turns(id, workspace_id, work_session_id, turn_index, user_message, created_by_user_id, created_at, updated_at) values (?, ?, ?, 0, 'Hi', ?, ?, ?)",
			turnId, fixture.workspaceId, fixture.sessionId, fixture.userId, Timestamp.from(now), Timestamp.from(now),
		)
		val runId = insertAgentRun(fixture.workspaceId, fixture.sessionId, fixture.userId, "key-run")
		jdbc.update(
			"insert into chat_response_versions(id, workspace_id, turn_id, version_index, agent_run_id, created_at, updated_at) values (?, ?, ?, 0, ?, ?, ?)",
			uuidGenerator.next(), fixture.workspaceId, turnId, runId, Timestamp.from(now), Timestamp.from(now),
		)

		// Second response version with the same agent_run_id in the same workspace fails unique constraint
		assertFailsWith<DataIntegrityViolationException> {
			jdbc.update(
				"insert into chat_response_versions(id, workspace_id, turn_id, version_index, agent_run_id, created_at, updated_at) values (?, ?, ?, 1, ?, ?, ?)",
				uuidGenerator.next(), fixture.workspaceId, turnId, runId, Timestamp.from(now), Timestamp.from(now),
			)
		}
	}

	@Test
	fun `execution envelope and transcripts store and cascade delete`() {
		val fixture = createFixture()
		val runId = insertAgentRun(fixture.workspaceId, fixture.sessionId, fixture.userId, "key-envelope")
		val envelopeId = uuidGenerator.next()
		jdbc.update(
			"""
			insert into chat_execution_envelopes(
				id, workspace_id, agent_run_id, fingerprint_version, envelope_fingerprint, generation_settings, created_at
			) values (?, ?, ?, 1, 'sha256-fingerprint', '{"promptVersion":"v1"}'::jsonb, ?)
			""".trimIndent(),
			envelopeId, fixture.workspaceId, runId, Timestamp.from(now),
		)

		val transcriptId = uuidGenerator.next()
		jdbc.update(
			"""
			insert into chat_execution_transcript_entries(
				id, workspace_id, envelope_id, call_index, tool_name, normalized_arguments, bounded_result, created_at
			) values (?, ?, ?, 0, 'readWritingBlock', '{"id":"b1"}'::jsonb, '{"text":"body"}'::jsonb, ?)
			""".trimIndent(),
			transcriptId, fixture.workspaceId, envelopeId, Timestamp.from(now),
		)

		assertEquals(
			1,
			jdbc.queryForObject("select count(*) from chat_execution_transcript_entries where id = ?", Int::class.java, transcriptId),
		)

		// Deleting envelope cascades to transcript entries
		jdbc.update("delete from chat_execution_envelopes where id = ?", envelopeId)
		assertEquals(
			0,
			jdbc.queryForObject("select count(*) from chat_execution_transcript_entries where id = ?", Int::class.java, transcriptId),
		)
	}

	@Test
	fun `cross workspace references fail foreign key checks`() {
		val f1 = createFixture()
		val f2 = createFixture()
		val turnId = uuidGenerator.next()
		jdbc.update(
			"insert into chat_turns(id, workspace_id, work_session_id, turn_index, user_message, created_by_user_id, created_at, updated_at) values (?, ?, ?, 0, 'Hi', ?, ?, ?)",
			turnId, f1.workspaceId, f1.sessionId, f1.userId, Timestamp.from(now), Timestamp.from(now),
		)
		val runIdInF2 = insertAgentRun(f2.workspaceId, f2.sessionId, f2.userId, "f2-run")

		// Linking turn in f1 with run in f2 must fail composite FK
		assertFailsWith<DataIntegrityViolationException> {
			jdbc.update(
				"insert into chat_response_versions(id, workspace_id, turn_id, version_index, agent_run_id, created_at, updated_at) values (?, ?, ?, 0, ?, ?, ?)",
				uuidGenerator.next(), f1.workspaceId, turnId, runIdInF2, Timestamp.from(now), Timestamp.from(now),
			)
		}
	}

	private data class Fixture(val workspaceId: UUID, val userId: UUID, val sessionId: UUID)
}
