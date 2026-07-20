package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("generation-certification")
@TestPropertySource(properties = [
	"plot.github.enabled=false",
	"server.address=127.0.0.1",
])
class GitHubCertificationReadApiIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Test
	fun listsPersistedConnectionsWithoutProviderCredentials() {
		mockMvc.get("/api/github/connections")
			.andExpect {
				status { isOk() }
				jsonPath("$.length()") { value(0) }
			}
	}
}
