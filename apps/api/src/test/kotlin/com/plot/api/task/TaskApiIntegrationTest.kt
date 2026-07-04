package com.plot.api.task

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
class TaskApiIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@BeforeEach
	fun cleanDevTaskData() {
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
	fun createListDetailAndUpdateTask() {
		val sessionId = createSession("Task Session")

		mockMvc.post("/api/tasks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sessionId":"$sessionId","title":"  Draft Task  "}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.sessionId") { value(sessionId.toString()) }
			jsonPath("$.title") { value("Draft Task") }
			jsonPath("$.status") { value("QUEUED") }
			jsonPath("$.lastActivityAt") { exists() }
			jsonPath("$.createdAt") { exists() }
			jsonPath("$.updatedAt") { exists() }
			jsonPath("$.workspaceId") { doesNotExist() }
		}

		val taskId = findTaskIdByTitle("Draft Task")

		mockMvc.get("/api/tasks")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(taskId.toString()) }
				jsonPath("$[0].sessionId") { value(sessionId.toString()) }
				jsonPath("$[0].title") { value("Draft Task") }
				jsonPath("$[0].status") { value("QUEUED") }
				jsonPath("$[0].workspaceId") { doesNotExist() }
			}

		mockMvc.get("/api/tasks/$taskId")
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(taskId.toString()) }
				jsonPath("$.sessionId") { value(sessionId.toString()) }
				jsonPath("$.title") { value("Draft Task") }
				jsonPath("$.status") { value("QUEUED") }
				jsonPath("$.workspaceId") { doesNotExist() }
			}

		mockMvc.patch("/api/tasks/$taskId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"  Updated Task  "}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.id") { value(taskId.toString()) }
			jsonPath("$.sessionId") { value(sessionId.toString()) }
			jsonPath("$.title") { value("Updated Task") }
			jsonPath("$.status") { value("QUEUED") }
			jsonPath("$.lastActivityAt") { exists() }
			jsonPath("$.updatedAt") { exists() }
			jsonPath("$.workspaceId") { doesNotExist() }
		}
	}

	@Test
	fun createWithoutSessionSucceeds() {
		mockMvc.post("/api/tasks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"Standalone Task"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.sessionId") { isEmpty() }
			jsonPath("$.title") { value("Standalone Task") }
			jsonPath("$.status") { value("QUEUED") }
			jsonPath("$.workspaceId") { doesNotExist() }
		}
	}

	@Test
	fun listOrdersTasksByCreatedAtDescending() {
		val olderId = UUID.randomUUID()
		val newerId = UUID.randomUUID()
		insertTask(
			id = olderId,
			title = "Older Task",
			createdAt = Instant.parse("2026-01-01T00:00:00Z"),
		)
		insertTask(
			id = newerId,
			title = "Newer Task",
			createdAt = Instant.parse("2026-01-02T00:00:00Z"),
		)

		mockMvc.get("/api/tasks")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(newerId.toString()) }
				jsonPath("$[1].id") { value(olderId.toString()) }
			}
	}

	@Test
	fun createReturnsBadRequestForInvalidSessionId() {
		val randomUuid = UUID.randomUUID()

		mockMvc.post("/api/tasks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sessionId":"$randomUuid","title":"Draft Task"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
			jsonPath("$.message") { value("Work session not found") }
		}
	}

	@Test
	fun createRejectsBlankTitle() {
		mockMvc.post("/api/tasks") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":" "}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
	}

	@Test
	fun updateRejectsBlankTitle() {
		val taskId = UUID.randomUUID()
		insertTask(
			id = taskId,
			title = "Draft Task",
			createdAt = Instant.parse("2026-01-01T00:00:00Z"),
		)

		mockMvc.patch("/api/tasks/$taskId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":" "}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
	}

	@Test
	fun getReturnsNotFoundForRandomUuid() {
		val randomUuid = UUID.randomUUID()

		mockMvc.get("/api/tasks/$randomUuid")
			.andExpect {
				status { isNotFound() }
				jsonPath("$.error") { value("NOT_FOUND") }
			}
	}

	@Test
	fun getReturnsBadRequestForMalformedTaskId() {
		mockMvc.get("/api/tasks/not-a-uuid")
			.andExpect {
				status { isBadRequest() }
				jsonPath("$.error") { value("BAD_REQUEST") }
			}
	}

	@Test
	fun patchReturnsNotFoundForRandomUuid() {
		val randomUuid = UUID.randomUUID()

		mockMvc.patch("/api/tasks/$randomUuid") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"Updated Task"}"""
		}.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
	}

	@Test
	fun patchReturnsBadRequestForMalformedTaskId() {
		mockMvc.patch("/api/tasks/not-a-uuid") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"Updated Task"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
	}

	private fun createSession(title: String): UUID {
		mockMvc.post("/api/sessions") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"title":"$title"}"""
		}.andExpect {
			status { isOk() }
		}

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

	private fun findTaskIdByTitle(title: String): UUID {
		return jdbcTemplate.queryForObject(
			"""
			select id
			from tasks
			where workspace_id = ? and title = ?
			""".trimIndent(),
			UUID::class.java,
			devContext.devWorkspaceId,
			title,
		)!!
	}

	private fun insertTask(
		id: UUID,
		title: String,
		createdAt: Instant,
	) {
		val timestamp = Timestamp.from(createdAt)
		jdbcTemplate.update(
			"""
			insert into tasks (
				id,
				workspace_id,
				work_session_id,
				title,
				status,
				created_by_user_id,
				last_activity_at,
				created_at,
				updated_at
			)
			values (?, ?, null, ?, 'QUEUED', ?, ?, ?, ?)
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			title,
			devContext.devUserId,
			timestamp,
			timestamp,
			timestamp,
		)
	}
}
