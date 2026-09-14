package com.plot.api.autonomy.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.autonomy.signal.SignalActivityProjectionService
import com.plot.api.autonomy.signal.SignalEnvelope
import com.plot.api.autonomy.signal.SignalEvaluationPersistence
import com.plot.api.autonomy.signal.SignalInbox
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class GitHubSignalProjectionIntegrationTest {
	@Autowired private lateinit var inbox: SignalInbox
	@Autowired private lateinit var projection: GitHubSignalProjection
	@Autowired private lateinit var evaluations: SignalEvaluationPersistence
	@Autowired private lateinit var jdbc: JdbcTemplate
	private val workspaces = mutableSetOf<UUID>()

	@AfterEach
	fun cleanup() {
		workspaces.forEach { workspaceId ->
			jdbc.update("delete from signal_evaluations where workspace_id = ?", workspaceId)
			jdbc.update("delete from chat_response_versions where workspace_id = ?", workspaceId)
			jdbc.update("delete from chat_turns where workspace_id = ?", workspaceId)
			jdbc.update("delete from agent_runs where workspace_id = ?", workspaceId)
			jdbc.update("delete from work_sessions where workspace_id = ?", workspaceId)
			jdbc.update("delete from github_release_draft_requests where workspace_id = ?", workspaceId)
			jdbc.update("delete from autonomy_signal_heads where workspace_id = ?", workspaceId)
			jdbc.update("delete from autonomy_signals where workspace_id = ?", workspaceId)
			jdbc.update("delete from github_webhook_deliveries where external_delivery_id like ?", "signal-projection-$workspaceId%")
			jdbc.update("delete from source_scopes where workspace_id = ?", workspaceId)
			jdbc.update("delete from source_namespaces where workspace_id = ?", workspaceId)
			jdbc.update("delete from workspaces where id = ?", workspaceId)
			jdbc.update("delete from users where email = ?", "signal-projection-$workspaceId@example.test")
		}
		workspaces.clear()
	}

	@Test
	fun `queued release signal is not finalized before admission and later converges to its chat response`() {
		val fixture = fixture()
		val receivedAt = Instant.parse("2026-09-14T00:00:00Z")
		val signalId = inbox.accept(
			SignalEnvelope(
				fixture.workspaceId, fixture.namespaceId, fixture.scopeId, "GITHUB", "release-signal-${fixture.workspaceId}",
				"release:v1.2.3", "release", null, "{}",
			),
			receivedAt,
		).id
		insertQueuedRelease(fixture, "v1.2.3")

		projection.projectNext()

		assertNull(evaluations.findBySignalId(fixture.workspaceId, signalId))

		val versionId = insertChatResponse(fixture)
		assertEquals(1, evaluations.recordReleaseAdmission(fixture.workspaceId, fixture.scopeId, "v1.2.3", fixture.agentRunId, receivedAt.plusSeconds(1)))

		val evaluation = requireNotNull(evaluations.findBySignalId(fixture.workspaceId, signalId))
		assertEquals("ADMITTED", evaluation.outcome)
		assertEquals(versionId, evaluation.admittedResponseVersionId)
		val activity = SignalActivityProjectionService(sql).projectActivity(
			fixture.workspaceId, setOf(fixture.scopeId), highWaterMark = receivedAt.plusSeconds(2),
		)
		assertEquals(versionId, activity.items.single().responseVersionId)
	}

	private fun fixture(): Fixture {
		val workspaceId = UUID.randomUUID()
		val userId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val workSessionId = UUID.randomUUID()
		val agentRunId = UUID.randomUUID()
		workspaces += workspaceId
		jdbc.update("insert into users(id, email, display_name, status, created_at, updated_at) values (?, ?, 'Signal tester', 'ACTIVE', now(), now())", userId, "signal-projection-$workspaceId@example.test")
		jdbc.update("insert into workspaces(id, name, slug, status, created_at, updated_at) values (?, 'Signal projection', ?, 'ACTIVE', now(), now())", workspaceId, "signal-projection-$workspaceId")
		jdbc.update("""insert into source_namespaces(id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'TENANT', ?, 'ACTIVE', now(), now())""", namespaceId, workspaceId, "namespace-$workspaceId")
		jdbc.update("""insert into source_scopes(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'Release repository', 'ACTIVE', now(), now())""", scopeId, workspaceId, namespaceId, "scope-$workspaceId")
		jdbc.update("insert into work_sessions(id, workspace_id, title, status, created_by_user_id, created_at, updated_at) values (?, ?, 'Release Chat', 'ACTIVE', ?, now(), now())", workSessionId, workspaceId, userId)
		jdbc.update("""insert into agent_runs(
			id, workspace_id, work_session_id, created_by_user_id, origin, idempotency_key, request_fingerprint,
			instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot, status, current_step,
			attempt_count, max_attempts, created_at, updated_at
		) values (?, ?, ?, ?, 'CHAT', ?, ?, 'Release notes', 'chat-agent-v1', 'read-only-v1', '{}'::jsonb,
			'QUEUED', 0, 0, 3, now(), now())""", agentRunId, workspaceId, workSessionId, userId, "signal-agent-$agentRunId", "signal-fingerprint-$agentRunId")
		return Fixture(workspaceId, userId, namespaceId, scopeId, workSessionId, agentRunId)
	}

	private fun insertQueuedRelease(fixture: Fixture, tagName: String) {
		val deliveryId = UUID.randomUUID()
		jdbc.update("""insert into github_webhook_deliveries(id, external_delivery_id, event_type, event_action, payload_hash, disposition, received_at)
			values (?, ?, 'release', 'published', ?, 'QUEUED', ?)""", deliveryId, "signal-projection-${fixture.workspaceId}-$tagName", "0".repeat(64), Timestamp.from(Instant.parse("2026-09-14T00:00:00Z")))
		jdbc.update("""insert into github_release_draft_requests(
			id, workspace_id, source_scope_id, initial_delivery_id, tag_name, status, transition_version, created_at, updated_at
		) values (?, ?, ?, ?, ?, 'QUEUED', 0, now(), now())""", UUID.randomUUID(), fixture.workspaceId, fixture.scopeId, deliveryId, tagName)
	}

	private fun insertChatResponse(fixture: Fixture): UUID {
		val turnId = UUID.randomUUID()
		val versionId = UUID.randomUUID()
		jdbc.update("""insert into chat_turns(id, workspace_id, work_session_id, turn_index, user_message, created_by_user_id, created_at, updated_at)
			values (?, ?, ?, 0, 'Write release notes', ?, now(), now())""", turnId, fixture.workspaceId, fixture.workSessionId, fixture.userId)
		jdbc.update("""insert into chat_response_versions(
			id, workspace_id, turn_id, version_index, agent_run_id, initiator_user_id, lineage_parent_version_id, is_active, created_at, updated_at
		) values (?, ?, ?, 0, ?, ?, null, true, now(), now())""", versionId, fixture.workspaceId, turnId, fixture.agentRunId, fixture.userId)
		return versionId
	}

	@Autowired private lateinit var sql: com.plot.api.persistence.JooqSqlExecutor

	private data class Fixture(
		val workspaceId: UUID,
		val userId: UUID,
		val namespaceId: UUID,
		val scopeId: UUID,
		val workSessionId: UUID,
		val agentRunId: UUID,
	)
}
