package com.plot.api.auth

import com.plot.api.TestcontainersConfiguration
import com.plot.api.auth.jwt.PlotJwtService
import com.plot.api.auth.persistence.AuthAccountRecord
import com.plot.api.auth.persistence.AuthAccountRepository
import com.plot.api.auth.persistence.AuthUserRecord
import com.plot.api.auth.persistence.AuthUserRepository
import com.plot.api.auth.session.AuthSessionService
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockCookie
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.crypto.password.PasswordEncoder
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
	"plot.auth.allowed-emails=member@example.com,signup@example.com",
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
	@Autowired private lateinit var authAccountRepository: AuthAccountRepository
	@Autowired private lateinit var passwordEncoder: PasswordEncoder
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

		mockMvc.post("/api/auth/sign-out") { cookie(sessionCookie) }.andExpect {
			status { isNoContent() }
		}

		mockMvc.get("/api/auth/session") { cookie(sessionCookie) }.andExpect {
			status { isUnauthorized() }
		}
	}

	@Test
	fun passwordSignInCreatesSessionForApprovedCredential() {
		val now = Instant.parse("2026-01-01T00:00:00Z")
		val user = authUserRepository.save(AuthUserRecord(
			id = "password-user-test",
			name = "Password Member",
			email = "member@example.com",
			emailVerified = true,
			image = null,
			createdAt = now,
			updatedAt = now,
		))
		authAccountRepository.save(AuthAccountRecord(
			id = "password-account-test",
			accountId = user.id,
			providerId = "credential",
			issuer = "local:password",
			userId = user.id,
			accessToken = null,
			refreshToken = null,
			scope = null,
			password = passwordEncoder.encode("correct horse battery staple"),
			createdAt = now,
			updatedAt = now,
		))

		val response = mockMvc.post("/api/auth/sign-in/password") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"email":"MEMBER@example.com","password":"correct horse battery staple"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.user.email") { value("member@example.com") }
			jsonPath("$.session.id") { exists() }
		}.andReturn()

		val sessionCookie = response.response.getCookie("plot.session")
		assertNotNull(sessionCookie)
		mockMvc.get("/api/auth/session") { cookie(sessionCookie) }.andExpect {
			status { isOk() }
			jsonPath("$.user.id") { value("password-user-test") }
		}

		mockMvc.post("/api/auth/sign-in/password") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"email":"member@example.com","password":"wrong password"}"""
		}.andExpect {
			status { isUnauthorized() }
			jsonPath("$.error") { value("INVALID_CREDENTIALS") }
		}
	}

	@Test
	fun passwordSignUpCreatesApprovedCredentialAndRejectsDuplicate() {
		val password = "correct horse battery staple"
		val response = mockMvc.post("/api/auth/sign-up/password") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"email":"SIGNUP@example.com","password":"$password"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.user.email") { value("signup@example.com") }
			jsonPath("$.user.name") { value("signup") }
			jsonPath("$.session.id") { exists() }
		}.andReturn()

		val user = assertNotNull(authUserRepository.findByEmailIgnoreCase("signup@example.com"))
		val account = assertNotNull(authAccountRepository.findByUserIdAndProviderId(user.id, "credential"))
		assertEquals("local:password", account.issuer)
		val passwordHash = assertNotNull(account.password)
		assertTrue(passwordEncoder.matches(password, passwordHash))

		val sessionCookie = assertNotNull(response.response.getCookie("plot.session"))
		mockMvc.get("/api/auth/session") { cookie(sessionCookie) }.andExpect {
			status { isOk() }
			jsonPath("$.user.id") { value(user.id) }
		}

		mockMvc.post("/api/auth/sign-up/password") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"email":"signup@example.com","password":"another password"}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("EMAIL_ALREADY_REGISTERED") }
		}
	}

	@Test
	fun passwordSignUpRejectsUnapprovedEmail() {
		mockMvc.post("/api/auth/sign-up/password") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"email":"outside@example.com","password":"correct horse battery staple"}"""
		}.andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("SIGN_UP_NOT_ALLOWED") }
		}
	}
}
