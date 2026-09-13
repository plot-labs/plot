package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, GitHubProductConnectionCredentialTest.Config::class)
@ActiveProfiles("local")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.github.enabled=true",
	"plot.github.app-id=1",
	"plot.github.app-slug=plot",
	"plot.github.private-key=test-key",
	"plot.github.state-secret=test-state-secret",
	"plot.github.product-oauth-client-id=test-client",
	"plot.github.product-oauth-client-secret=test-secret",
	"plot.github.product-oauth-redirect-uri=http://127.0.0.1:3000/api/plot/github/oauth/callback",
	"plot.github.product-credential-encryption-key=01234567890123456789012345678901",
	"server.address=127.0.0.1",
])
class GitHubProductConnectionCredentialTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var oauthClient: FakeGitHubProductOAuthClient

	@Autowired
	private lateinit var backfillService: GitHubCredentialBackfillService

	@Autowired
	private lateinit var productCredentialRepository: GitHubProductCredentialRepository

	@BeforeEach
	fun cleanProductCredentialState() {
		jdbcTemplate.update("delete from github_product_oauth_states")
		jdbcTemplate.update("delete from github_product_credentials")
		jdbcTemplate.update("delete from auth_account where provider_id = 'github'")
		jdbcTemplate.update("delete from connections where workspace_id = ?", devContext.devWorkspaceId)
		oauthClient.reset()
	}

	@Test
	fun productOAuthStoresEncryptedCredentialAndActivatesExistingInstallation() {
		val start = mockMvc.post("/api/github/oauth/start")
			.andExpect { status { isOk() }; jsonPath("$.authorizationUrl") { value(org.hamcrest.Matchers.containsString("client_id=test-client")) } }
			.andReturn().response.contentAsString
		val state = Regex("[?&]state=([A-Za-z0-9_-]+)").find(start)!!.groupValues[1]

		val callback = mockMvc.get("/api/github/oauth/callback?code=oauth-code&state=$state")
			.andExpect {
				status { isOk() }
				jsonPath("$.connectionId") { exists() }
				jsonPath("$.githubAccountLogin") { value("acme") }
				jsonPath("$.errorCode") { value(org.hamcrest.Matchers.nullValue()) }
			}
			.andReturn().response.contentAsString

		assertTrue(callback.contains("connectionId"))
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from github_product_credentials where user_id = ? and status = 'ACTIVE'",
			Int::class.java,
			devContext.devUserId,
		))
		val ciphertext = jdbcTemplate.queryForObject(
			"select access_token_ciphertext from github_product_credentials where user_id = ?",
			String::class.java,
			devContext.devUserId,
		)
		assertFalse(ciphertext!!.contains("oauth-access-token"))
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from auth_account where user_id = 'auth-user-dev' and provider_id = 'github'",
			Int::class.java,
		))

		mockMvc.get("/api/github/oauth/callback?code=oauth-code&state=$state")
			.andExpect { status { isBadRequest() }; jsonPath("$.error") { value("INVALID_GITHUB_PRODUCT_OAUTH_STATE") } }
	}

	@Test
	fun missingProductScopeDoesNotPersistCredential() {
		oauthClient.scope = "read:user user:email"
		val start = mockMvc.post("/api/github/oauth/start").andReturn().response.contentAsString
		val state = Regex("[?&]state=([A-Za-z0-9_-]+)").find(start)!!.groupValues[1]

		mockMvc.get("/api/github/oauth/callback?code=oauth-code&state=$state")
			.andExpect { status { isUnauthorized() }; jsonPath("$.error") { value("GITHUB_SCOPE_REQUIRED") } }
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from github_product_credentials where user_id = ?",
			Int::class.java,
			devContext.devUserId,
		))
	}

	@Test
	fun credentialBackfillUsesPlotAuthSubjectCheckpointAndQuarantinesNothingForAnExactMapping() {
		jdbcTemplate.update(
			"""
			update github_product_credential_backfill
			set last_source_id = null, state = 'PENDING', processed_count = 0,
			    migrated_count = 0, quarantined_count = 0, last_error = null,
			    started_at = null, completed_at = null, updated_at = now()
			""".trimIndent(),
		)
		jdbcTemplate.update("delete from github_product_credential_quarantine")
		jdbcTemplate.update("update users set auth_subject = 'legacy-auth-user' where id = ?", devContext.devUserId)
		jdbcTemplate.update(
			"""
			insert into auth_user (id, name, email, email_verified, created_at, updated_at)
			values ('legacy-auth-user', 'Legacy User', 'legacy@plot.local', true, now(), now())
			on conflict (id) do nothing
			""".trimIndent(),
		)
		jdbcTemplate.update(
			"""
			insert into auth_account (id, account_id, provider_id, issuer, user_id, access_token, scope, created_at, updated_at)
			values ('acct-backfill', '9010', 'github', 'legacy:github', 'legacy-auth-user', 'legacy-token', 'read:user user:email read:org', now(), now())
			on conflict (id) do update set access_token = excluded.access_token, scope = excluded.scope, updated_at = excluded.updated_at
			""".trimIndent(),
		)

		val report = backfillService.runBatch(10)

		assertEquals("COMPLETED", report.state)
		assertEquals(1, report.migratedCount)
		assertEquals(0, report.quarantinedCount)
		assertEquals("legacy-token", productCredentialRepository.findActiveByUserId(devContext.devUserId)?.accessToken)
		assertTrue(jdbcTemplate.queryForObject(
			"select access_token_ciphertext from github_product_credentials where user_id = ?",
			String::class.java,
			devContext.devUserId,
		)!!.contains("legacy-token").not())
	}

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun fakeGitHubClient() = FakeGitHubClient()

		@Bean
		@Primary
		fun fakeGitHubProductOAuthClient() = FakeGitHubProductOAuthClient()
	}
}

class FakeGitHubProductOAuthClient : GitHubProductOAuthClient {
	var scope = "read:user user:email read:org"

	var profile = GitHubProductProfile(9001, "acme")

	override fun authorizationUrl(state: String): String = "https://github.com/login/oauth/authorize?client_id=test-client&state=$state"

	override fun exchangeCode(code: String): GitHubProductOAuthToken = GitHubProductOAuthToken(
		accessToken = "oauth-access-token",
		refreshToken = "oauth-refresh-token",
		scope = scope,
	)

	override fun fetchProfile(accessToken: String): GitHubProductProfile = profile

	fun reset() {
		scope = "read:user user:email read:org"
		profile = GitHubProductProfile(9001, "acme")
	}
}
