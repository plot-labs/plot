package com.plot.api.auth

import com.plot.api.TestcontainersConfiguration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/**
 * Owner-only actions must reject an authenticated MEMBER with 403 and let an
 * OWNER through. The role comes from the WorkOS token, so each request is made
 * twice with the same user and only the role claim changed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, WorkOSMembershipFakeConfiguration::class)
@ActiveProfiles("integration")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=false",
	"plot.workos.enabled=true",
	"plot.workos.api-key=sk_test_owner_guard",
	"plot.workos.client-id=client_test_owner_guard",
	"plot.workos.issuer=https://issuer.workos.test",
	"plot.workos.audience=plot-api",
	"plot.workos.jwks-uri=https://issuer.workos.test/.well-known/jwks.json",
	"plot.github.enabled=true",
	"plot.github.app-id=1",
	"plot.github.app-slug=plot",
	"plot.github.private-key=test-key",
	"plot.github.state-secret=test-state-secret",
])
class WorkspaceOwnerGuardIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate

	@BeforeEach
	fun setUp() {
		deleteFixtures()
		jdbcTemplate.update(
			"""
			insert into users (id, email, display_name, status, created_at, updated_at)
			values (?, ?, 'Owner Guard User', 'ACTIVE', now(), now())
			""".trimIndent(), USER_ID, EMAIL,
		)
		jdbcTemplate.update(
			"""
			insert into workspaces (
			  id, name, slug, created_by_user_id, status, plan, entitlement_status, access_mode,
			  public_citations_enabled, created_at, updated_at
			) values (?, 'Owner Guard Workspace', 'owner-guard-workspace', ?, 'ACTIVE', 'none', 'subscription_required', 'full', true, now(), now())
			""".trimIndent(), WORKSPACE_ID, USER_ID,
		)
		jdbcTemplate.update(
			"""
			insert into workspace_members (
			  id, workspace_id, user_id, role, status, joined_at, created_at, updated_at
			) values (?, ?, ?, 'MEMBER', 'ACTIVE', now(), now(), now())
			""".trimIndent(), MEMBER_ID, WORKSPACE_ID, USER_ID,
		)
		jdbcTemplate.update(
			"""
			insert into workos_identity_mappings (workos_user_id, plot_user_id, email, email_verified, created_at, updated_at)
			values (?, ?, ?, true, now(), now())
			""".trimIndent(), WORKOS_USER_ID, USER_ID, EMAIL,
		)
		jdbcTemplate.update(
			"""
			insert into workos_organization_mappings (workos_organization_id, workos_user_id, workspace_id, created_at, updated_at)
			values (?, ?, ?, now(), now())
			""".trimIndent(), ORGANIZATION_ID, WORKOS_USER_ID, WORKSPACE_ID,
		)
	}

	@AfterEach
	fun tearDown() = deleteFixtures()

	@Test
	fun contentProfileUpdateIsOwnerOnly() {
		val update: MockHttpServletRequestDsl.() -> Unit = {
			contentType = MediaType.APPLICATION_JSON
			content = """{"tone":"Plain and direct"}"""
		}

		mockMvc.put("/api/content-profile") { update(); asRole("member") }
			.andExpectOwnerRequired("Only workspace owners can update content profile")
		mockMvc.put("/api/content-profile") { update(); asRole("owner") }
			.andExpect { status { isOk() } }
	}

	@Test
	fun unpublishIsOwnerOnly() {
		val path = "/api/artifact-variants/${UUID.randomUUID()}/unpublish"

		mockMvc.post(path) { asRole("member") }.andExpectOwnerRequired()
		// The variant does not exist, so reaching NOT_FOUND shows the owner passed the guard.
		mockMvc.post(path) { asRole("owner") }.andExpect { status { isNotFound() } }
	}

	@Test
	fun githubInstallationRequestIsOwnerOnly() {
		mockMvc.post("/api/github/installations/requests") { asRole("member") }.andExpectOwnerRequired()
		mockMvc.post("/api/github/installations/requests") { asRole("owner") }
			.andExpect { status { is2xxSuccessful() } }
	}

	@Test
	fun githubImportIsOwnerOnly() {
		val path = "/api/github/repositories/${UUID.randomUUID()}/imports"
		val to = Instant.now().truncatedTo(ChronoUnit.SECONDS)
		val import: MockHttpServletRequestDsl.() -> Unit = {
			contentType = MediaType.APPLICATION_JSON
			content = """{"from":"${to.minus(1, ChronoUnit.DAYS)}","to":"$to"}"""
		}

		mockMvc.post(path) { import(); asRole("member") }.andExpectOwnerRequired()
		mockMvc.post(path) { import(); asRole("owner") }.andExpect { status { isNotFound() } }
	}

	@Test
	fun releaseActivityRetryIsOwnerOnly() {
		val path = "/api/github/repositories/${UUID.randomUUID()}/release-activity/${UUID.randomUUID()}/retry"

		mockMvc.post(path) { asRole("member") }.andExpectOwnerRequired()
		mockMvc.post(path) { asRole("owner") }.andExpect { status { isNotFound() } }
	}

	private fun MockHttpServletRequestDsl.asRole(role: String) {
		with(jwt().jwt { token ->
			token.issuer("https://issuer.workos.test").subject(WORKOS_USER_ID).audience(listOf("plot-api"))
				.claim("org_id", ORGANIZATION_ID).claim("role", role)
		})
	}

	private fun ResultActionsDsl.andExpectOwnerRequired(message: String = "Workspace owner access is required") {
		andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("FORBIDDEN") }
			jsonPath("$.message") { value(message) }
		}
	}

	private fun deleteFixtures() {
		jdbcTemplate.update("delete from github_installation_states where workspace_id = ?", WORKSPACE_ID)
		jdbcTemplate.update("delete from workspace_content_profiles where workspace_id = ?", WORKSPACE_ID)
		jdbcTemplate.update("delete from workspace_content_profile_revisions where workspace_id = ?", WORKSPACE_ID)
		jdbcTemplate.update("delete from workos_organization_mappings where workos_user_id = ?", WORKOS_USER_ID)
		jdbcTemplate.update("delete from workos_identity_mappings where workos_user_id = ?", WORKOS_USER_ID)
		jdbcTemplate.update("delete from workspace_members where id = ?", MEMBER_ID)
		jdbcTemplate.update("delete from workspaces where id = ?", WORKSPACE_ID)
		jdbcTemplate.update("delete from users where id = ?", USER_ID)
	}

	private companion object {
		val USER_ID: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000201")
		val WORKSPACE_ID: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000202")
		val MEMBER_ID: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000203")
		const val WORKOS_USER_ID = "user_workos_owner_guard"
		const val ORGANIZATION_ID = "org_workos_owner_guard"
		const val EMAIL = "owner-guard@example.com"
	}
}
