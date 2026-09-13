package com.plot.api.worksession

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true", "server.address=127.0.0.1"])
class WorkSessionApiIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@BeforeEach
	fun cleanDevSessions() {
		jdbcTemplate.update(
			"delete from generation_runs where workspace_id = ? and agent_run_id is not null",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from artifact_runs where workspace_id = ?",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from agent_steps where workspace_id = ?",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from agent_run_inputs where workspace_id = ?",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from agent_run_sources where workspace_id = ?",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from agent_runs where workspace_id = ?",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"update generation_runs set work_session_id = null where workspace_id = ? and work_session_id is not null",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from work_sessions where workspace_id = ?",
			devContext.devWorkspaceId,
		)
	}


	@Test
	fun listOrdersSessionsByLatestActivity() {
		val olderId = UUID.randomUUID()
		val newerId = UUID.randomUUID()
		insertSession(
			id = olderId,
			title = "Older Session",
			createdAt = Instant.parse("2026-01-01T00:00:00Z"),
			lastActivityAt = Instant.parse("2026-01-03T00:00:00Z"),
		)
		insertSession(
			id = newerId,
			title = "Newer Session",
			createdAt = Instant.parse("2026-01-02T00:00:00Z"),
		)

		mockMvc.get("/api/sessions")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(olderId.toString()) }
				jsonPath("$[1].id") { value(newerId.toString()) }
			}
	}

	@Test
	fun listSessionAgentRunsReturnsEveryChatRunInChronologicalOrder() {
		val sessionId = UUID.randomUUID()
		insertSession(sessionId, title = "Agent session", createdAt = Instant.parse("2026-01-01T00:00:00Z"))
		val firstRun = insertChatAgentRun(sessionId, Instant.parse("2026-01-01T01:00:00Z"), "Changelog")
		val secondRun = insertChatAgentRun(sessionId, Instant.parse("2026-01-01T02:00:00Z"), "Customer update")

		mockMvc.get("/api/sessions/$sessionId/agent-runs")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(firstRun.toString()) }
				jsonPath("$[0].instruction") { value("Changelog") }
				jsonPath("$[0].artifactWorkflowRunId") { doesNotExist() }
				jsonPath("$[1].id") { value(secondRun.toString()) }
				jsonPath("$[1].instruction") { value("Customer update") }
				jsonPath("$[1].artifactWorkflowRunId") { doesNotExist() }
			}
	}

	@Test
	fun listSessionTurnsReturnsTurnsWithVersionsAndEligibility() {
		val sessionId = UUID.randomUUID()
		insertSession(sessionId, title = "Agent session", createdAt = Instant.parse("2026-01-01T00:00:00Z"))
		val firstRun = insertChatAgentRun(sessionId, Instant.parse("2026-01-01T01:00:00Z"), "Changelog")
		val secondRun = insertChatAgentRun(sessionId, Instant.parse("2026-01-01T02:00:00Z"), "Customer update")

		mockMvc.get("/api/sessions/$sessionId/turns")
			.andExpect {
				status { isOk() }
				jsonPath("$.length()") { value(2) }
				jsonPath("$[0].turnIndex") { value(0) }
				jsonPath("$[0].userMessage") { value("Changelog") }
				jsonPath("$[0].versions.length()") { value(1) }
				jsonPath("$[0].versions[0].agentRunId") { value(firstRun.toString()) }
				jsonPath("$[0].versions[0].retryEligibility.eligible") { value(false) }
				jsonPath("$[0].versions[0].retryEligibility.reason") { value("NOT_LATEST_TURN") }
				jsonPath("$[1].turnIndex") { value(1) }
				jsonPath("$[1].userMessage") { value("Customer update") }
				jsonPath("$[1].versions.length()") { value(1) }
				jsonPath("$[1].versions[0].agentRunId") { value(secondRun.toString()) }
			}
	}

	@Test
	fun retryChatResponseCreatesNewVersionAndIsIdempotent() {
		val sessionId = UUID.randomUUID()
		insertSession(sessionId, title = "Agent session", createdAt = Instant.parse("2026-01-01T00:00:00Z"))
		val runId = insertChatAgentRun(sessionId, Instant.parse("2026-01-01T01:00:00Z"), "Changelog draft")

		val turnsResponse = mockMvc.get("/api/sessions/$sessionId/turns")
			.andExpect { status { isOk() } }
			.andReturn()
		val mapper = tools.jackson.databind.ObjectMapper()
		val tree = mapper.readTree(turnsResponse.response.contentAsString)
		val versionId = tree[0]["versions"][0]["id"].asText()

		mockMvc.post("/api/agent-runs/versions/$versionId/retry") {
			header("Idempotency-Key", "retry-key-1")
		}.andExpect {
			status { isAccepted() }
			jsonPath("$.versionIndex") { value(1) }
			jsonPath("$.status") { value("QUEUED") }
		}

		mockMvc.post("/api/agent-runs/versions/$versionId/retry") {
			header("Idempotency-Key", "retry-key-1")
		}.andExpect {
			status { isAccepted() }
			jsonPath("$.versionIndex") { value(1) }
			jsonPath("$.status") { value("QUEUED") }
		}

		mockMvc.get("/api/sessions/$sessionId/turns")
			.andExpect {
				status { isOk() }
				jsonPath("$.length()") { value(1) }
				jsonPath("$[0].versions.length()") { value(2) }
				jsonPath("$[0].versions[0].versionIndex") { value(0) }
				jsonPath("$[0].versions[0].retryEligibility.eligible") { value(false) }
				jsonPath("$[0].versions[0].retryEligibility.reason") { value("NOT_LATEST_VERSION") }
				jsonPath("$[0].versions[1].versionIndex") { value(1) }
				jsonPath("$[0].versions[1].status") { value("QUEUED") }
				jsonPath("$[0].versions[1].retryEligibility.eligible") { value(false) }
				jsonPath("$[0].versions[1].retryEligibility.reason") { value("RUN_NOT_TERMINAL") }
			}
	}



	private fun insertSession(
		id: UUID,
		workspaceId: UUID = devContext.devWorkspaceId,
		title: String,
		createdAt: Instant,
		lastActivityAt: Instant = createdAt,
	) {
		val createdTimestamp = Timestamp.from(createdAt)
		val activityTimestamp = Timestamp.from(lastActivityAt)
		jdbcTemplate.update(
			"""
			insert into work_sessions (
				id,
				workspace_id,
				title,
				status,
				created_by_user_id,
				last_activity_at,
				created_at,
				updated_at
			)
			values (?, ?, ?, 'OPEN', ?, ?, ?, ?)
			""".trimIndent(),
			id,
			workspaceId,
			title,
			devContext.devUserId,
			activityTimestamp,
			createdTimestamp,
			createdTimestamp,
		)
	}

	private fun insertChatAgentRun(sessionId: UUID, createdAt: Instant, instruction: String): UUID {
		val id = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into agent_runs (
				id, workspace_id, routine_execution_id, routine_id, work_session_id, created_by_user_id,
				origin, idempotency_key, request_fingerprint, instruction_snapshot,
				prompt_version, tool_policy_version, budget_snapshot, status,
				current_step, attempt_count, max_attempts, created_at, updated_at
			) values (?, ?, null, null, ?, ?, 'CHAT', ?, ?, ?, 'chat-agent-v1', 'read-only-v1', '{}'::jsonb,
				'SUCCEEDED', 0, 0, 3, ?, ?)
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			sessionId,
			devContext.devUserId,
			"session-agent-$id",
			"fingerprint-$id",
			instruction,
			Timestamp.from(createdAt),
			Timestamp.from(createdAt),
		)
		return id
	}
}
