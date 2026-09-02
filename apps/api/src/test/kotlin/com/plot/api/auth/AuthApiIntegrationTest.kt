package com.plot.api.auth

import com.plot.api.TestcontainersConfiguration
import com.plot.api.auth.jwt.PlotJwtService
import com.plot.api.auth.persistence.AuthUserRecord
import com.plot.api.auth.persistence.AuthUserRepository
import com.plot.api.auth.session.AuthSessionService
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockCookie
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("integration")
@TestPropertySource(properties = [
	"plot.auth.allowed-emails=member@example.com",
	"plot.auth.issuer=https://app.useplot.xyz",
	"plot.auth.audience=plot-api",
	"plot.auth.enabled=true",
	"plot.auth.required=true",
	"plot.dev-bootstrap.enabled=false",
])
class AuthApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var authSessionService: AuthSessionService
	@Autowired private lateinit var authUserRepository: AuthUserRepository
	@Autowired private lateinit var plotJwtService: PlotJwtService

	@BeforeEach
	fun cleanAuthTables() {
		jdbcTemplate.update("delete from auth_session")
		jdbcTemplate.update("delete from auth_account")
		jdbcTemplate.update("delete from auth_user")
		jdbcTemplate.update("delete from auth_jwks")
	}

	@Test
	fun sessionTokenJwksAndSignOutFlow() {
		val now = Instant.parse("2026-01-01T00:00:00Z")
		val user = authUserRepository.save(AuthUserRecord(
			id = "auth-user-test",
			name = "Plot Member",
			email = "member@example.com",
			emailVerified = true,
			image = null,
			createdAt = now,
			updatedAt = now,
		))
		val request = MockHttpServletRequest()
		val authenticated = authSessionService.createSession(user, request)
		val sessionCookie = MockCookie("plot.session", authenticated.session.token)

		mockMvc.get("/api/auth/session") { cookie(sessionCookie) }.andExpect {
			status { isOk() }
			jsonPath("$.user.id") { value("auth-user-test") }
			jsonPath("$.user.email") { value("member@example.com") }
		}

		val tokenResult = mockMvc.get("/api/auth/token") { cookie(sessionCookie) }.andExpect {
			status { isOk() }
			jsonPath("$.token") { exists() }
		}.andReturn()

		val token = Regex(""""token"\s*:\s*"([^"]+)"""")
			.find(tokenResult.response.contentAsString)
			?.groupValues
			?.get(1)
		assertNotNull(token)
		val jwt = plotJwtService.decoder().decode(token)
		assertEquals("auth-user-test", jwt.subject)

		mockMvc.get("/api/auth/jwks").andExpect {
			status { isOk() }
			jsonPath("$.keys") { isArray() }
		}

		val signOutResult = mockMvc.post("/api/auth/sign-out") { cookie(sessionCookie) }.andReturn()
		assertEquals(204, signOutResult.response.status, signOutResult.response.contentAsString)

		mockMvc.get("/api/auth/session") { cookie(sessionCookie) }.andExpect {
			status { isUnauthorized() }
		}
	}
}
