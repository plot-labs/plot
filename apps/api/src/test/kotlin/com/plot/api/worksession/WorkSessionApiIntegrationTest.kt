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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
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
			"update generation_runs set work_session_id = null where workspace_id = ? and work_session_id is not null",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from work_sessions where workspace_id = ?",
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun createListAndUpdateSession() {
		mockMvc.post("/api/sessions") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"  Draft Session  "}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Draft Session") }
			jsonPath("$.status") { value("OPEN") }
			jsonPath("$.latestGenerationId") { doesNotExist() }
			jsonPath("$.lastActivityAt") { exists() }
			jsonPath("$.createdAt") { exists() }
			jsonPath("$.updatedAt") { exists() }
			jsonPath("$.workspaceId") { doesNotExist() }
		}

		val sessionId = findSessionIdByTitle("Draft Session")

		mockMvc.get("/api/sessions")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(sessionId.toString()) }
				jsonPath("$[0].title") { value("Draft Session") }
				jsonPath("$[0].status") { value("OPEN") }
				jsonPath("$[0].latestGenerationId") { doesNotExist() }
				jsonPath("$[0].workspaceId") { doesNotExist() }
			}

		mockMvc.patch("/api/sessions/$sessionId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"  Updated Session  "}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.id") { value(sessionId.toString()) }
			jsonPath("$.title") { value("Updated Session") }
			jsonPath("$.status") { value("OPEN") }
			jsonPath("$.lastActivityAt") { exists() }
			jsonPath("$.updatedAt") { exists() }
			jsonPath("$.workspaceId") { doesNotExist() }
		}
	}

	@Test
	fun updateLinksTheLatestGenerationAndRefreshesActivity() {
		val sessionId = UUID.randomUUID()
		insertSession(sessionId, title = "Linked Session", createdAt = Instant.parse("2026-01-01T00:00:00Z"))
		val generationId = UUID.randomUUID()
		insertGeneration(generationId)
		jdbcTemplate.update("update work_sessions set last_activity_at = now() - interval '1 minute' where id = ?", sessionId)
		val before = jdbcTemplate.queryForObject("select last_activity_at from work_sessions where id = ?", Instant::class.java, sessionId)!!

		mockMvc.patch("/api/sessions/$sessionId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"latestGenerationId":"$generationId"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Linked Session") }
			jsonPath("$.latestGenerationId") { value(generationId.toString()) }
			jsonPath("$.lastActivityAt") { exists() }
		}

		val after = jdbcTemplate.queryForObject("select last_activity_at from work_sessions where id = ?", Instant::class.java, sessionId)!!
		check(after.isAfter(before))
	}

	@Test
	fun updateRejectsMissingOrOtherWorkspaceGeneration() {
		val sessionId = UUID.randomUUID()
		insertSession(sessionId, title = "Scoped Session", createdAt = Instant.parse("2026-01-01T00:00:00Z"))
		val missingGenerationId = UUID.randomUUID()

		mockMvc.patch("/api/sessions/$sessionId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"latestGenerationId":"$missingGenerationId"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("INVALID_GENERATION") }
		}

		val otherWorkspaceId = insertOtherWorkspace()
		val otherGenerationId = UUID.randomUUID()
		insertGeneration(otherGenerationId, otherWorkspaceId)
		mockMvc.patch("/api/sessions/$sessionId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"latestGenerationId":"$otherGenerationId"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("INVALID_GENERATION") }
		}
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
	fun listSessionGenerationsReturnsEveryLinkedRunInChronologicalOrder() {
		val sessionId = UUID.randomUUID()
		insertSession(sessionId, title = "Artifact session", createdAt = Instant.parse("2026-01-01T00:00:00Z"))
		val firstRun = UUID.randomUUID()
		val secondRun = UUID.randomUUID()
		insertGeneration(firstRun, createdAt = Instant.parse("2026-01-01T01:00:00Z"), workSessionId = sessionId, instruction = "Changelog")
		insertGeneration(secondRun, createdAt = Instant.parse("2026-01-01T02:00:00Z"), workSessionId = sessionId, instruction = "Customer update")
		val artifactId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into content_packs (id, workspace_id, generation_run_id, title, status, created_at, updated_at)
			values (?, ?, ?, 'Customer update', 'READY', now(), now())
			""".trimIndent(),
			artifactId, devContext.devWorkspaceId, secondRun,
		)

		mockMvc.get("/api/sessions/$sessionId/generations")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(firstRun.toString()) }
				jsonPath("$[0].instruction") { value("Changelog") }
				jsonPath("$[0].artifact") { doesNotExist() }
				jsonPath("$[1].id") { value(secondRun.toString()) }
				jsonPath("$[1].instruction") { value("Customer update") }
				jsonPath("$[1].artifact.id") { value(artifactId.toString()) }
				jsonPath("$[1].artifact.title") { value("Customer update") }
			}
	}

	@Test
	fun patchReturnsNotFoundForRandomUuid() {
		val randomUuid = UUID.randomUUID()

		mockMvc.patch("/api/sessions/$randomUuid") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"Updated Session"}"""
		}.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
	}

	@Test
	fun patchReturnsBadRequestForMalformedSessionId() {
		mockMvc.patch("/api/sessions/not-a-uuid") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"Updated Session"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
	}

	private fun findSessionIdByTitle(title: String): UUID {
		return jdbcTemplate.queryForObject(
			"""
			select id
			from work_sessions
			where workspace_id = ? and title = ?
			""".trimIndent(),
			UUID::class.java,
			devContext.devWorkspaceId,
			title,
		)!!
	}

	private fun insertOtherWorkspace(): UUID {
		val workspaceId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at)
			values (?, 'Other Session Workspace', ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			workspaceId,
			"other-session-${workspaceId}",
			devContext.devUserId,
		)
		return workspaceId
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

	private fun insertGeneration(
		id: UUID,
		workspaceId: UUID = devContext.devWorkspaceId,
		createdAt: Instant = Instant.now(),
		workSessionId: UUID? = null,
		instruction: String? = null,
	) {
		jdbcTemplate.update(
			"""
			insert into generation_runs (
				id, workspace_id, work_session_id, created_by_user_id, idempotency_key, request_fingerprint,
				status, workflow_version, prompt_version, output_schema_version, budget_version,
				provider, model_name, budget_snapshot, user_instruction, created_at, updated_at
			)
			values (?, ?, ?, ?, ?, ?, 'QUEUED', 'fixed-v1', 'test-v1', 'generation-v5', 'budget-v1',
				'TEST', 'test-model', '{}'::jsonb, ?, ?, ?)
			""".trimIndent(),
			id,
			workspaceId,
			workSessionId,
			devContext.devUserId,
			"session-test-$id",
			"fingerprint-$id",
			instruction,
			Timestamp.from(createdAt),
			Timestamp.from(createdAt),
		)
	}
}
