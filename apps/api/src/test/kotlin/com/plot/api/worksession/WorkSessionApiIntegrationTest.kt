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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
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
			"delete from tasks where workspace_id = ?",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from work_sessions where workspace_id = ?",
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun createListDetailAndUpdateSession() {
		mockMvc.post("/api/sessions") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"  Draft Session  "}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Draft Session") }
			jsonPath("$.status") { value("OPEN") }
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
				jsonPath("$[0].workspaceId") { doesNotExist() }
			}

		mockMvc.get("/api/sessions/$sessionId")
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(sessionId.toString()) }
				jsonPath("$.title") { value("Draft Session") }
				jsonPath("$.status") { value("OPEN") }
				jsonPath("$.workspaceId") { doesNotExist() }
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
	fun listOrdersSessionsByCreatedAtDescending() {
		val olderId = UUID.randomUUID()
		val newerId = UUID.randomUUID()
		insertSession(
			id = olderId,
			title = "Older Session",
			createdAt = Instant.parse("2026-01-01T00:00:00Z"),
		)
		insertSession(
			id = newerId,
			title = "Newer Session",
			createdAt = Instant.parse("2026-01-02T00:00:00Z"),
		)

		mockMvc.get("/api/sessions")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(newerId.toString()) }
				jsonPath("$[1].id") { value(olderId.toString()) }
			}
	}

	@Test
	fun getReturnsNotFoundForRandomUuid() {
		val randomUuid = UUID.randomUUID()

		mockMvc.get("/api/sessions/$randomUuid")
			.andExpect {
				status { isNotFound() }
				jsonPath("$.error") { value("NOT_FOUND") }
			}
	}

	@Test
	fun getReturnsNotFoundForSessionInAnotherWorkspace() {
		val otherWorkspaceId = insertOtherWorkspace()
		val otherSessionId = UUID.randomUUID()
		insertSession(
			id = otherSessionId,
			workspaceId = otherWorkspaceId,
			title = "Other Workspace Session",
			createdAt = Instant.parse("2026-01-03T00:00:00Z"),
		)

		mockMvc.get("/api/sessions/$otherSessionId")
			.andExpect {
				status { isNotFound() }
				jsonPath("$.error") { value("NOT_FOUND") }
			}
	}

	@Test
	fun getReturnsBadRequestForMalformedSessionId() {
		mockMvc.get("/api/sessions/not-a-uuid")
			.andExpect {
				status { isBadRequest() }
				jsonPath("$.error") { value("BAD_REQUEST") }
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
	) {
		val timestamp = Timestamp.from(createdAt)
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
			timestamp,
			timestamp,
			timestamp,
		)
	}
}
