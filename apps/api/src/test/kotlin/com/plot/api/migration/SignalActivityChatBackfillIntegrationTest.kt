package com.plot.api.migration

import com.plot.api.TestcontainersConfiguration
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SignalActivityChatBackfillIntegrationTest {
	@Autowired private lateinit var service: SignalActivityChatBackfillService
	@Autowired private lateinit var jdbc: JdbcTemplate
	private val fixtures = mutableListOf<Fixture>()

	@AfterEach
	fun cleanup() {
		fixtures.forEach { fixture ->
			jdbc.update("delete from signal_evaluations where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from legacy_activity_provenance where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from chat_execution_envelopes where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from chat_response_versions where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from chat_turns where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from agent_runs where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from work_sessions where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from autonomy_signals where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from autonomy_opportunities where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from source_scopes where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from source_namespaces where workspace_id = ?", fixture.workspaceId)
			jdbc.update("delete from workspaces where id = ?", fixture.workspaceId)
			jdbc.update("delete from users where id = ?", fixture.userId)
			jdbc.update("delete from signal_activity_chat_backfill_checkpoints where checkpoint_key like ?", "${fixture.checkpointKey}%")
		}
		fixtures.clear()
	}

	@Test
	fun `bounded batches resume from durable phase cursors and exclude post-watermark rows`() {
		val watermark = Instant.parse("2000-01-01T00:00:00Z")
		val fixture = fixture(watermark.minusSeconds(30))
		insertLegacyRun(fixture, watermark.minusSeconds(10))
		insertOpportunity(fixture, watermark.minusSeconds(10))
		insertSignal(fixture, watermark.minusSeconds(10), "before-watermark")

		val interrupted = service.run(
			checkpointKey = fixture.checkpointKey,
			requestedBatchSize = 1,
			maxBatches = 1,
			initialWatermark = watermark,
		)

		assertEquals("IN_PROGRESS", interrupted.status)
		assertEquals(1, interrupted.attemptedCount)
		assertEquals(1, interrupted.insertedCount)
		assertEquals(1, jdbc.queryForObject("select count(*) from chat_response_versions where workspace_id = ?", Int::class.java, fixture.workspaceId))
		assertNotNull(interrupted.phases.single { it.phase == "runs" }.lastProcessedId)

		insertSignal(fixture, watermark.plusSeconds(1), "after-watermark")
		val resumed = service.run(
			checkpointKey = fixture.checkpointKey,
			requestedBatchSize = 1,
			maxBatches = 20,
			initialWatermark = watermark.plusSeconds(60),
		)

		assertEquals("COMPLETED", resumed.status)
		assertEquals(watermark, resumed.watermark)
		assertEquals(3, resumed.attemptedCount)
		assertEquals(3, resumed.insertedCount)
		assertEquals(0, resumed.skippedCount)
		assertEquals(0, resumed.lag?.totalLag)
		assertEquals(1, jdbc.queryForObject("select count(*) from legacy_activity_provenance where workspace_id = ?", Int::class.java, fixture.workspaceId))
		assertEquals(1, jdbc.queryForObject("select count(*) from signal_evaluations where workspace_id = ?", Int::class.java, fixture.workspaceId))
		assertEquals(0, jdbc.queryForObject(
			"select count(*) from signal_evaluations where workspace_id = ? and input_fingerprint = 'after-watermark'",
			Int::class.java,
			fixture.workspaceId,
		))
	}

	private fun fixture(createdAt: Instant): Fixture {
		val fixture = Fixture(
			workspaceId = UUID.randomUUID(),
			userId = UUID.randomUUID(),
			namespaceId = UUID.randomUUID(),
			scopeId = UUID.randomUUID(),
			workSessionId = UUID.randomUUID(),
			checkpointKey = "backfill-${UUID.randomUUID()}",
		)
		fixtures += fixture
		val time = Timestamp.from(createdAt)
		jdbc.update("insert into users(id, email, display_name, status, created_at, updated_at) values (?, ?, 'Backfill tester', 'ACTIVE', ?, ?)", fixture.userId, "backfill-${fixture.workspaceId}@example.test", time, time)
		jdbc.update("insert into workspaces(id, name, slug, status, created_at, updated_at) values (?, 'Backfill workspace', ?, 'ACTIVE', ?, ?)", fixture.workspaceId, "backfill-${fixture.workspaceId}", time, time)
		jdbc.update("""insert into source_namespaces(id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'TENANT', ?, 'ACTIVE', ?, ?)""", fixture.namespaceId, fixture.workspaceId, "namespace-${fixture.namespaceId}", time, time)
		jdbc.update("""insert into source_scopes(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'Backfill repository', 'ACTIVE', ?, ?)""", fixture.scopeId, fixture.workspaceId, fixture.namespaceId, "scope-${fixture.scopeId}", time, time)
		jdbc.update("""insert into work_sessions(id, workspace_id, title, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'Backfill Chat', 'ACTIVE', ?, ?, ?)""", fixture.workSessionId, fixture.workspaceId, fixture.userId, time, time)
		return fixture
	}

	private fun insertLegacyRun(fixture: Fixture, createdAt: Instant) {
		val id = UUID.randomUUID()
		val time = Timestamp.from(createdAt)
		jdbc.update("""insert into agent_runs(
			id, workspace_id, work_session_id, created_by_user_id, origin, idempotency_key, request_fingerprint,
			instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot, status, current_step,
			attempt_count, max_attempts, created_at, updated_at
		) values (?, ?, ?, ?, 'CHAT', ?, ?, 'Historical chat', 'chat-agent-v1', 'read-only-v1', '{}'::jsonb,
			'SUCCEEDED', 0, 0, 3, ?, ?)""", id, fixture.workspaceId, fixture.workSessionId, fixture.userId,
			"backfill-run-$id", "fingerprint-$id", time, time)
	}

	private fun insertOpportunity(fixture: Fixture, createdAt: Instant) {
		val time = Timestamp.from(createdAt)
		jdbc.update("""insert into autonomy_opportunities(
			id, workspace_id, source_scope_id, subject_key, title, disposition, reason, evidence_ids, missing_facts, dismissed, version, created_at, updated_at
		) values (?, ?, ?, ?, 'Historical opportunity', 'AWAITING_EVIDENCE', 'Historical reason', '[]'::jsonb, '[]'::jsonb, false, 0, ?, ?)""",
			UUID.randomUUID(), fixture.workspaceId, fixture.scopeId, "opportunity-${UUID.randomUUID()}", time, time)
	}

	private fun insertSignal(fixture: Fixture, receivedAt: Instant, deliveryKey: String) {
		val time = Timestamp.from(receivedAt)
		jdbc.update("""insert into autonomy_signals(
			id, workspace_id, source_namespace_id, source_scope_id, provider, delivery_key, object_key, event_type, schema_version,
			payload, received_at, state, attempts, available_at
		) values (?, ?, ?, ?, 'GITHUB', ?, ?, 'release', 1, '{}'::jsonb, ?, 'SUCCEEDED', 0, ?)""",
			UUID.randomUUID(), fixture.workspaceId, fixture.namespaceId, fixture.scopeId, deliveryKey, "object-$deliveryKey", time, time)
	}

	private data class Fixture(
		val workspaceId: UUID,
		val userId: UUID,
		val namespaceId: UUID,
		val scopeId: UUID,
		val workSessionId: UUID,
		val checkpointKey: String,
	)
}
