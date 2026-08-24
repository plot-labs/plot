package com.plot.api.workspace

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.http.MediaType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class WorkspaceApiIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Test
	fun detailReturnsSelectedWorkspace() {
		mockMvc.get("/api/workspaces/${devContext.devWorkspaceId}")
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(devContext.devWorkspaceId.toString()) }
				jsonPath("$.status") { value("ACTIVE") }
				jsonPath("$.plan") { value("founding") }
				jsonPath("$.entitlementStatus") { value("active") }
				jsonPath("$.accessMode") { value("full") }
				jsonPath("$.role") { value("OWNER") }
			}
	}

	@Test
	fun detailHidesOtherWorkspaces() {
		mockMvc.get("/api/workspaces/${UUID.randomUUID()}")
			.andExpect {
				status { isNotFound() }
				jsonPath("$.error") { value("NOT_FOUND") }
			}
	}

	@Test
	fun ownerCanCreateWorkspace() {
		mockMvc.post("/api/workspaces") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Studio"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Studio") }
			jsonPath("$.status") { value("ACTIVE") }
			jsonPath("$.role") { value("OWNER") }
		}
	}

	@Test
	fun workspaceCreationIsCappedPerUser() {
		deleteExtraWorkspaces()
		try {
			// The bootstrap workspace already occupies one slot of the cap.
			repeat(2) { index ->
				mockMvc.post("/api/workspaces") {
					contentType = MediaType.APPLICATION_JSON
					content = """{"name":"Capped $index"}"""
				}.andExpect { status { isOk() } }
			}
			mockMvc.post("/api/workspaces") {
				contentType = MediaType.APPLICATION_JSON
				content = """{"name":"One too many"}"""
			}.andExpect {
				status { isForbidden() }
				jsonPath("$.error") { value("WORKSPACE_LIMIT_REACHED") }
			}
			assertEquals(
				3,
				jdbcTemplate.queryForObject(
					"select count(*) from workspace_members where user_id = ? and status = 'ACTIVE'",
					Int::class.java,
					devContext.devUserId,
				),
			)
		} finally {
			deleteExtraWorkspaces()
		}
	}

	private fun deleteExtraWorkspaces() {
		jdbcTemplate.update(
			"delete from workspace_members where user_id = ? and workspace_id <> ?",
			devContext.devUserId,
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from workspaces where created_by_user_id = ? and id <> ?",
			devContext.devUserId,
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun ownerCanUpdateWorkspaceProfile() {
		mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Product","logoUrl":"data:image/png;base64,abc"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Product") }
			jsonPath("$.logoUrl") { value("data:image/png;base64,abc") }
			jsonPath("$.role") { value("OWNER") }
		}

		mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Personal","logoUrl":""}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Personal") }
			jsonPath("$.logoUrl") { doesNotExist() }
		}

		org.junit.jupiter.api.Assertions.assertEquals(
			"Personal",
			jdbcTemplate.queryForObject(
				"select name from workspaces where id = ?",
				String::class.java,
				devContext.devWorkspaceId,
			),
		)
	}
}
