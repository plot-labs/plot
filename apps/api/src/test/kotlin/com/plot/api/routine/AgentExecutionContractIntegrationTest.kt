package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class AgentExecutionContractIntegrationTest {

	@Autowired
	private lateinit var mockMvc: MockMvc

	@Test
	fun `legacy direct artifact execution route is no longer public`() {
		mockMvc.post("/api/generations")
			.andExpect { status { isNotFound() } }
	}

	@Test
	fun `Chat activity is exposed through Agent runs`() {
		mockMvc.get("/api/sessions/${UUID.randomUUID()}/agent-runs")
			.andExpect { status { isNotFound() } }
	}
}
