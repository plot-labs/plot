package com.plot.api.auth

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class WorkspaceSelectionOptionalApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	@BeforeEach
	fun linkAuthenticatedIdentity() {
		jdbcTemplate.update(
			"update users set auth_issuer = ?, auth_subject = ? where id = ?",
			ISSUER,
			SUBJECT,
			devContext.devUserId,
		)
	}

	@AfterEach
	fun unlinkAuthenticatedIdentity() {
		jdbcTemplate.update(
			"update users set auth_issuer = null, auth_subject = null where id = ?",
			devContext.devUserId,
		)
	}

	@Test
	fun accountAndWorkspaceDiscoveryDoNotRequireWorkspaceHeader() {
		val authenticated = jwt().jwt { token ->
			token.issuer(ISSUER).subject(SUBJECT)
		}

		mockMvc.perform(get("/api/me").with(authenticated))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.defaultWorkspaceId").value(devContext.devWorkspaceId.toString()))

		mockMvc.perform(get("/api/workspaces").with(authenticated))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$[0].id").value(devContext.devWorkspaceId.toString()))
	}

	private companion object {
		const val ISSUER = "https://app.useplot.xyz"
		const val SUBJECT = "workspace-selection-optional"
	}
}
