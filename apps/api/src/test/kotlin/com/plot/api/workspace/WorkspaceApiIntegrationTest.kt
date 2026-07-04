package com.plot.api.workspace

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class WorkspaceApiIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var devContext: DevContext

	@Test
	fun listReturnsDevWorkspace() {
		mockMvc.get("/api/workspaces")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(devContext.devWorkspaceId.toString()) }
				jsonPath("$[0].status") { value("ACTIVE") }
			}
	}

	@Test
	fun detailReturnsDevWorkspace() {
		mockMvc.get("/api/workspaces/${devContext.devWorkspaceId}")
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(devContext.devWorkspaceId.toString()) }
				jsonPath("$.status") { value("ACTIVE") }
			}
	}

	@Test
	fun detailReturnsNotFoundForNonDevWorkspace() {
		val randomUuid = UUID.randomUUID()

		mockMvc.get("/api/workspaces/$randomUuid")
			.andExpect {
				status { isNotFound() }
				jsonPath("$.error") { value("NOT_FOUND") }
			}
	}

	@Test
	fun patchUpdatesNameAndSlug() {
		mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Plot Dev","slug":"plot-dev"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.name") { value("Plot Dev") }
			jsonPath("$.slug") { value("plot-dev") }
			jsonPath("$.status") { value("ACTIVE") }
		}
	}

	@Test
	fun patchReturnsNotFoundForNonDevWorkspace() {
		val randomUuid = UUID.randomUUID()

		mockMvc.patch("/api/workspaces/$randomUuid") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Plot Dev","slug":"plot-dev"}"""
		}.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
	}

	@Test
	fun patchRejectsBlankName() {
		mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":" ","slug":"plot-dev"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
	}

	@Test
	fun patchRejectsBlankSlug() {
		mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Plot Dev","slug":" "}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
	}
}
