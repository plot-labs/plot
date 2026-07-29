package com.plot.api.workspace

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

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
}
