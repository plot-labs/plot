package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.UuidGenerator
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import com.plot.api.migration.SignalActivityChatBackfillService
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ChatResponseVersionMigrationIntegrationTest {
	@Autowired private lateinit var jdbc: JdbcTemplate
	@Autowired private lateinit var uuidGenerator: UuidGenerator
	@Autowired private lateinit var backfillService: SignalActivityChatBackfillService

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

	@Test
	fun `two legacy AgentRuns in one Chat become ordered one-version turns`() {
		val fixture = createFixture()
		val run1 = insertAgentRun(fixture.workspaceId, fixture.sessionId, fixture.userId, "legacy-key-1")
		val run2 = insertAgentRun(fixture.workspaceId, fixture.sessionId, fixture.userId, "legacy-key-2")

		val report = backfillService.runBackfill("test-legacy-runs", now.plusSeconds(3600))
		assertTrue(report.reconciledRuns >= 2)

		val turns = jdbc.query(
			"select id, turn_index, user_message from chat_turns where workspace_id = ? and work_session_id = ? order by turn_index asc",
			{ rs, _ -> rs.getInt("turn_index") to rs.getString("user_message") },
			fixture.workspaceId,
			fixture.sessionId,
		)
		assertEquals(2, turns.size)
		assertEquals(0, turns[0].first)
		assertEquals(1, turns[1].first)

		val versions = jdbc.query(
			"select agent_run_id, version_index from chat_response_versions where workspace_id = ? order by version_index asc",
			{ rs, _ -> rs.getObject("agent_run_id", UUID::class.java) to rs.getInt("version_index") },
			fixture.workspaceId,
		)
		assertEquals(2, versions.size)
		assertEquals(0, versions[0].second)
		assertEquals(0, versions[1].second)
	}

	@Test
	fun `moving a retry from pending to running does not permit a second active AgentRun for that turn`() {
		val fixture = createFixture()
		val turnId = uuidGenerator.next()
		jdbc.update(
			"insert into chat_turns(id, workspace_id, work_session_id, turn_index, user_message, created_by_user_id, created_at, updated_at) values (?, ?, ?, 0, 'Hi', ?, ?, ?)",
			turnId, fixture.workspaceId, fixture.sessionId, fixture.userId, Timestamp.from(now), Timestamp.from(now),
		)
		val run1 = insertAgentRun(fixture.workspaceId, fixture.sessionId, fixture.userId, "run-pending")
		val run2 = insertAgentRun(fixture.workspaceId, fixture.sessionId, fixture.userId, "run-second-active")

		// Insert first version as active (pending/running)
		jdbc.update(
			"insert into chat_response_versions(id, workspace_id, turn_id, version_index, agent_run_id, is_active, created_at, updated_at) values (?, ?, ?, 0, ?, true, ?, ?)",
			uuidGenerator.next(), fixture.workspaceId, turnId, run1, Timestamp.from(now), Timestamp.from(now),
		)

		// Inserting second active version for same turn must fail single active partial unique index
		assertFailsWith<DataIntegrityViolationException> {
			jdbc.update(
				"insert into chat_response_versions(id, workspace_id, turn_id, version_index, agent_run_id, is_active, created_at, updated_at) values (?, ?, ?, 1, ?, true, ?, ?)",
				uuidGenerator.next(), fixture.workspaceId, turnId, run2, Timestamp.from(now), Timestamp.from(now),
			)
		}

		// Once first is terminal (is_active = false), second can be inserted as active
		jdbc.update("update chat_response_versions set is_active = false where agent_run_id = ?", run1)
		jdbc.update(
			"insert into chat_response_versions(id, workspace_id, turn_id, version_index, agent_run_id, is_active, created_at, updated_at) values (?, ?, ?, 1, ?, true, ?, ?)",
			uuidGenerator.next(), fixture.workspaceId, turnId, run2, Timestamp.from(now), Timestamp.from(now),
		)
		val activeCount = jdbc.queryForObject(
			"select count(*) from chat_response_versions where workspace_id = ? and turn_id = ? and is_active = true",
			Int::class.java,
			fixture.workspaceId,
			turnId,
		)
		assertEquals(1, activeCount)
	}

	@Test
	fun `resumable backfill catches rows committed after initial high-water mark`() {
		val fixture = createFixture()
		val t1 = now
		val run1 = insertAgentRun(fixture.workspaceId, fixture.sessionId, fixture.userId, "run-t1")

		// First pass at t1
		val r1 = backfillService.runBackfill("catchup-test", t1)
		assertTrue(r1.reconciledRuns >= 1)

		// New run at t2 > t1
		val t2 = now.plusSeconds(120)
		val run2Id = uuidGenerator.next()
		jdbc.update(
			"""
			insert into agent_runs(
				id, workspace_id, work_session_id, created_by_user_id, origin, idempotency_key,
				request_fingerprint, instruction_snapshot, prompt_version, tool_policy_version,
				budget_snapshot, status, created_at, updated_at
			) values (?, ?, ?, ?, 'CHAT', 'run-t2', 'fp-t2', 'inst2', 'v1', 'v1', '{}'::jsonb, 'SUCCEEDED', ?, ?)
			""".trimIndent(),
			run2Id, fixture.workspaceId, fixture.sessionId, fixture.userId, Timestamp.from(t2), Timestamp.from(t2),
		)

		// Before second pass, lag is 1
		val lagBefore = backfillService.computeLag(t2)
		assertEquals(1L, lagBefore.unreconciledRuns)

		// Second pass at t2 catches run 2
		val r2 = backfillService.runBackfill("catchup-test", t2)
		assertEquals(1, r2.reconciledRuns)
		assertEquals(0L, r2.lag.totalLag)
		assertEquals("COMPLETED", r2.status)
	}

	private data class Fixture(val workspaceId: UUID, val userId: UUID, val sessionId: UUID)
}
