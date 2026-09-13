package com.plot.api.autonomy.signal

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.UuidGenerator
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.routine.ChatAgentAdmissionService
import com.plot.api.routine.dto.CreateChatAgentRunRequest
import com.plot.api.content.ContentType
import com.plot.api.autonomy.github.GitHubSignalProjection
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
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SignalEvaluationIntegrationTest {
	@Autowired private lateinit var jdbc: JdbcTemplate
	@Autowired private lateinit var uuidGenerator: UuidGenerator
	@Autowired private lateinit var inbox: SignalInbox
	@Autowired private lateinit var projection: GitHubSignalProjection
	@Autowired private lateinit var evaluationPersistence: SignalEvaluationPersistence
	@Autowired private lateinit var admissionService: ChatAgentAdmissionService

	private val workspaces = mutableListOf<UUID>()
	private val now = Instant.parse("2026-09-13T12:00:00Z")

	@AfterEach
	fun cleanup() {
		workspaces.forEach { id ->
			jdbc.update("delete from chat_execution_transcript_entries where workspace_id = ?", id)
			jdbc.update("delete from chat_execution_envelopes where workspace_id = ?", id)
			jdbc.update("delete from signal_evaluations where workspace_id = ?", id)
			jdbc.update("delete from chat_response_versions where workspace_id = ?", id)
			jdbc.update("delete from chat_turns where workspace_id = ?", id)
			jdbc.update("delete from autonomy_opportunities where workspace_id = ?", id)
			jdbc.update("delete from autonomy_missions where workspace_id = ?", id)
			jdbc.update("delete from autonomy_signal_heads where workspace_id = ?", id)
			jdbc.update("delete from autonomy_signals where workspace_id = ?", id)
			jdbc.update("delete from agent_run_inputs where workspace_id = ?", id)
			jdbc.update("delete from agent_run_sources where workspace_id = ?", id)
			jdbc.update("delete from agent_runs where workspace_id = ?", id)
			jdbc.update("delete from content_source_snapshots where workspace_id = ?", id)
			jdbc.update("delete from work_sessions where workspace_id = ?", id)
			jdbc.update("delete from connection_namespace_bindings where workspace_id = ?", id)
			jdbc.update("delete from connections where workspace_id = ?", id)
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
		val namespaceId = uuidGenerator.next()
		jdbc.update(
			"insert into source_namespaces(id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at) values (?, ?, 'GITHUB', 'ORGANIZATION', 'org', 'Org', 'ACTIVE', ?, ?)",
			namespaceId, workspaceId, Timestamp.from(now), Timestamp.from(now),
		)
		val scopeId = uuidGenerator.next()
		jdbc.update(
			"insert into source_scopes(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, display_name, status, created_at, updated_at) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', 'repo', 'Repo', 'ACTIVE', ?, ?)",
			scopeId, workspaceId, namespaceId, Timestamp.from(now), Timestamp.from(now),
		)
		val connectionId = uuidGenerator.next()
		jdbc.update(
			"insert into connections(id, workspace_id, provider, connection_kind, external_connection_key, status, created_at, updated_at) values (?, ?, 'GITHUB', 'APP', 'conn-1', 'ACTIVE', ?, ?)",
			connectionId, workspaceId, Timestamp.from(now), Timestamp.from(now),
		)
		jdbc.update(
			"insert into connection_namespace_bindings(id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at) values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', ?, ?, ?)",
			uuidGenerator.next(), workspaceId, connectionId, namespaceId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)
		return Fixture(workspaceId, userId, namespaceId, scopeId)
	}

	@Test
	fun `signal projection writes evaluation evidence while execution writer remains dormant`() {
		val fixture = createFixture()
		val receipt = inbox.accept(
			SignalEnvelope(
				workspaceId = fixture.workspaceId,
				sourceNamespaceId = fixture.namespaceId,
				sourceScopeId = fixture.scopeId,
				provider = "GITHUB",
				deliveryKey = "del-test-1",
				objectKey = "release:v1.0.0",
				eventType = "release",
				sourceVersion = now,
				payload = "{\"action\":\"published\"}",
			),
			now,
		)
		assertTrue(receipt.inserted)

		val projected = projection.projectNext()
		assertTrue(projected)

		val evaluation = evaluationPersistence.findBySignalId(fixture.workspaceId, receipt.id)
		assertNotNull(evaluation)
		assertEquals("NO_GENERATION", evaluation.outcome)
		assertTrue(evaluation.reason.contains("Release observed: v1.0.0"))

		// Check dormant writer invariant: legacy writer is active, new writer cannot be enabled
		assertFailsWith<IllegalStateException> {
			SignalEvaluationPersistence(
				com.plot.api.persistence.JooqSqlExecutor(org.jooq.impl.DSL.using(jdbc.dataSource!!, org.jooq.SQLDialect.POSTGRES)),
				uuidGenerator,
				newExecutionWriterEnabled = true,
			).assertWriterInvariants(legacyWriterActive = true)
		}
	}

	@Test
	fun `replaying a compatibility write creates no duplicate records`() {
		val fixture = createFixture()
		val runId = uuidGenerator.next()
		val chatId = uuidGenerator.next()
		jdbc.update(
			"insert into work_sessions(id, workspace_id, title, status, created_by_user_id, created_at, updated_at) values (?, ?, 'Chat', 'OPEN', ?, ?, ?)",
			chatId, fixture.workspaceId, fixture.userId, Timestamp.from(now), Timestamp.from(now),
		)
		jdbc.update(
			"""
			insert into agent_runs(
				id, workspace_id, work_session_id, created_by_user_id, origin, idempotency_key,
				request_fingerprint, instruction_snapshot, prompt_version, tool_policy_version,
				budget_snapshot, status, created_at, updated_at
			) values (?, ?, ?, ?, 'CHAT', 'key-replay', 'fp-replay', 'inst', 'v1', 'v1', '{}'::jsonb, 'SUCCEEDED', ?, ?)
			""".trimIndent(),
			runId, fixture.workspaceId, chatId, fixture.userId, Timestamp.from(now), Timestamp.from(now),
		)

		val writer = com.plot.api.routine.ChatCompatibilityWriter(
			com.plot.api.persistence.JooqSqlExecutor(org.jooq.impl.DSL.using(jdbc.dataSource!!, org.jooq.SQLDialect.POSTGRES)),
			uuidGenerator,
		)
		writer.recordDirectChatRun(
			workspaceId = fixture.workspaceId,
			userId = fixture.userId,
			chatId = chatId,
			runId = runId,
			instruction = "inst",
			fingerprint = "fp-replay",
			settingsJson = "{}",
			sourceSnapshotId = null,
			now = now,
		)
		// Replay write
		writer.recordDirectChatRun(
			workspaceId = fixture.workspaceId,
			userId = fixture.userId,
			chatId = chatId,
			runId = runId,
			instruction = "inst",
			fingerprint = "fp-replay",
			settingsJson = "{}",
			sourceSnapshotId = null,
			now = now,
		)

		val turnCount = jdbc.queryForObject(
			"select count(*) from chat_turns where workspace_id = ? and work_session_id = ?",
			Int::class.java,
			fixture.workspaceId,
			chatId,
		)
		val versionCount = jdbc.queryForObject(
			"select count(*) from chat_response_versions where workspace_id = ? and agent_run_id = ?",
			Int::class.java,
			fixture.workspaceId,
			runId,
		)
		val envelopeCount = jdbc.queryForObject(
			"select count(*) from chat_execution_envelopes where workspace_id = ? and agent_run_id = ?",
			Int::class.java,
			fixture.workspaceId,
			runId,
		)

		assertEquals(1, turnCount)
		assertEquals(1, versionCount)
		assertEquals(1, envelopeCount)
	}

	private data class Fixture(val workspaceId: UUID, val userId: UUID, val namespaceId: UUID, val scopeId: UUID)
}
