package com.plot.api.timeline

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.ApiException
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class TimelineIntegrationTest {

	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var timelineService: TimelineQueryService

	@BeforeEach
	fun setup() {
		devBootstrapService.bootstrap()
		jdbcTemplate.update("delete from github_release_draft_requests where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from github_webhook_deliveries")
		jdbcTemplate.update("delete from source_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connections where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_namespaces where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from agent_steps where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from agent_runs where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from work_sessions where workspace_id = ?", devContext.devWorkspaceId)
	}

	@Test
	fun `R-010 - returns timeline for session and prevents access from another workspace`() {
		val sessionId = UUID.randomUUID()
		insertWorkSession(sessionId, devContext.devWorkspaceId)

		val agentRunId = UUID.randomUUID()
		insertAgentRun(
			id = agentRunId,
			workspaceId = devContext.devWorkspaceId,
			workSessionId = sessionId,
			status = "RUNNING",
			failureCode = null,
		)

		val items = timelineService.listForSession(sessionId, devContext.devWorkspaceId)
		assertEquals(1, items.size)
		assertEquals(agentRunId, items[0].id)
		assertEquals("AGENT", items[0].stage)
		assertEquals("RUNNING", items[0].status)
		assertEquals("Running", items[0].statusLabel)

		// Access from unknown/inactive workspace is denied with 403
		val foreignWorkspaceId = UUID.randomUUID()
		val ex = assertFailsWith<ApiException> {
			timelineService.listForSession(sessionId, foreignWorkspaceId)
		}
		assertEquals(HttpStatus.FORBIDDEN, ex.status)
	}

	@Test
	fun `R-010 - sanitizes raw provider errors to safe error code`() {
		val sessionId = UUID.randomUUID()
		insertWorkSession(sessionId, devContext.devWorkspaceId)

		val agentRunId = UUID.randomUUID()
		// Insert with lowercase raw provider error message containing stack trace details
		insertAgentRun(
			id = agentRunId,
			workspaceId = devContext.devWorkspaceId,
			workSessionId = sessionId,
			status = "FAILED",
			failureCode = "failed to connect to api.openai.com:443 timeout after 30000ms secret=sk-12345",
		)

		val item = timelineService.getByExecutionId(agentRunId, devContext.devWorkspaceId)
		// Should sanitize to safe UNKNOWN_ERROR, NOT leak provider url, secrets or stack trace
		assertEquals("UNKNOWN_ERROR", item.safeErrorCode)
		assertEquals("FAILED", item.status)
		assertEquals("Failed", item.statusLabel)
	}

	@Test
	fun `R-011 - maps domain states to timeline labels and retry timestamps`() {
		val sessionId = UUID.randomUUID()
		insertWorkSession(sessionId, devContext.devWorkspaceId)

		val futureRetry = Instant.now().plusSeconds(300)

		// 1. Retry scheduled
		val run1 = UUID.randomUUID()
		insertAgentRun(
			id = run1,
			workspaceId = devContext.devWorkspaceId,
			workSessionId = sessionId,
			status = "QUEUED",
			nextAttemptAt = futureRetry,
		)

		// 2. Needs connection
		val run2 = UUID.randomUUID()
		insertAgentRun(
			id = run2,
			workspaceId = devContext.devWorkspaceId,
			workSessionId = sessionId,
			status = "FAILED",
			failureCode = "SOURCE_NOT_READY",
		)

		// 3. Ready for review
		val run3 = UUID.randomUUID()
		insertAgentRun(
			id = run3,
			workspaceId = devContext.devWorkspaceId,
			workSessionId = sessionId,
			status = "SUCCEEDED",
		)

		val items = timelineService.listForSession(sessionId, devContext.devWorkspaceId)
		val map = items.associateBy { it.id }

		assertEquals("Retry scheduled", map[run1]?.statusLabel)
		assertNotNull(map[run1]?.nextAttemptAt)

		assertEquals("Needs connection", map[run2]?.statusLabel)
		assertEquals("SOURCE_NOT_READY", map[run2]?.safeErrorCode)
		assertEquals("Reconnect repository access", map[run2]?.recoveryAction)

		assertEquals("Ready for review", map[run3]?.statusLabel)
	}

	@Test
	fun `R-012 - legacy runs with missing links return factual data without 500 error`() {
		val sessionId = UUID.randomUUID()
		insertWorkSession(sessionId, devContext.devWorkspaceId)

		val legacyRunId = UUID.randomUUID()
		// Legacy agent run with NO artifact workflow, NO routine execution, NO artifact pack
		insertAgentRun(
			id = legacyRunId,
			workspaceId = devContext.devWorkspaceId,
			workSessionId = sessionId,
			status = "QUEUED",
			routineExecutionId = null,
		)

		val item = timelineService.getByExecutionId(legacyRunId, devContext.devWorkspaceId)
		assertEquals(legacyRunId, item.id)
		assertEquals(sessionId, item.workSessionId)
		assertNull(item.routineExecutionId)
		assertNull(item.artifactWorkflowRunId)
		assertNull(item.artifactId)
		assertEquals("Waiting to start", item.statusLabel)
	}

	@Test
	fun `R-011 - maps release draft states and connection error code`() {
		val scopeId = insertSourceScope()
		val deliveryId = insertDelivery()

		val releaseId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into github_release_draft_requests (
				id, workspace_id, source_scope_id, initial_delivery_id, tag_name, status,
				error_code, transition_version, attempt_count, created_at, updated_at
			) values (?, ?, ?, ?, ?, 'FAILED', 'GITHUB_ACCESS_DENIED', 1, 1, now(), now())
			""".trimIndent(),
			releaseId, devContext.devWorkspaceId, scopeId, deliveryId, "v1.0.0",
		)

		val item = timelineService.getByExecutionId(releaseId, devContext.devWorkspaceId)
		assertEquals(releaseId, item.id)
		assertEquals("RELEASE", item.origin)
		assertEquals("RELEASE", item.stage)
		assertEquals("NEEDS_CONNECTION", item.status)
		assertEquals("Needs connection", item.statusLabel)
		assertEquals("GITHUB_ACCESS_DENIED", item.safeErrorCode)
		assertEquals("Reconnect repository access", item.recoveryAction)
	}

	private fun insertSourceScope(): UUID {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val repoNumber = kotlin.math.abs(UUID.randomUUID().hashCode())
		jdbcTemplate.update(
			"""insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at)
				values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())""".trimIndent(),
			connectionId, devContext.devWorkspaceId, "$repoNumber", devContext.devUserId,
		)
		jdbcTemplate.update(
			"""insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
				values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'ACTIVE', now(), now())""".trimIndent(),
			namespaceId, devContext.devWorkspaceId, "repository:$repoNumber",
		)
		jdbcTemplate.update(
			"""insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at)
				values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())""".trimIndent(),
			bindingId, devContext.devWorkspaceId, connectionId, namespaceId,
		)
		jdbcTemplate.update(
			"""insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, external_key, display_name, metadata, status, created_at, updated_at)
				values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, ?, '{"defaultBranch":"main"}'::jsonb, 'ACTIVE', now(), now())""".trimIndent(),
			scopeId, devContext.devWorkspaceId, namespaceId, "$repoNumber", "acme/repo-$repoNumber", "acme/repo-$repoNumber",
		)
		return scopeId
	}

	private fun insertDelivery(): UUID {
		val id = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into github_webhook_deliveries (
				id, external_delivery_id, event_type, event_action, installation_id, repository_id,
				payload_hash, disposition, received_at
			) values (?, ?, 'release', 'published', 77, 99, ?, 'QUEUED', now())
			""".trimIndent(),
			id, "ext-$id", "a".repeat(64),
		)
		return id
	}

	private fun insertWorkSession(id: UUID, workspaceId: UUID) {
		jdbcTemplate.update(
			"""
			insert into work_sessions (id, workspace_id, title, status, created_by_user_id, last_activity_at, created_at, updated_at)
			values (?, ?, 'Test session', 'OPEN', ?, now(), now(), now())
			""".trimIndent(),
			id, workspaceId, devContext.devUserId,
		)
	}

	private fun insertAgentRun(
		id: UUID,
		workspaceId: UUID,
		workSessionId: UUID,
		status: String,
		failureCode: String? = null,
		nextAttemptAt: Instant? = null,
		routineExecutionId: UUID? = null,
	) {
		jdbcTemplate.update(
			"""
			insert into agent_runs (
				id, workspace_id, work_session_id, routine_execution_id, created_by_user_id,
				instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot, status,
				attempt_count, max_attempts, next_attempt_at, failure_code, origin,
				idempotency_key, request_fingerprint, created_at, updated_at
			) values (?, ?, ?, ?, ?, 'instructions', 'v1', 'v1', '{}'::jsonb, ?, 0, 3, ?, ?, 'CHAT', ?, ?, now(), now())
			""".trimIndent(),
			id, workspaceId, workSessionId, routineExecutionId, devContext.devUserId,
			status, nextAttemptAt?.let(Timestamp::from), failureCode, "idem-$id", "fp-$id",
		)
	}
}
