package com.plot.api.auth

import com.plot.api.TestcontainersConfiguration
import com.plot.api.auth.workos.WorkOSMembershipGateway
import com.plot.api.auth.workos.WorkOSMembershipSnapshot
import com.workos.webhooks.Webhook
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, WorkOSMembershipFakeConfiguration::class)
@ActiveProfiles("integration")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=false",
	"plot.workos.enabled=true",
	"plot.workos.api-key=sk_test_membership",
	"plot.workos.client-id=client_test_membership",
	"plot.workos.issuer=https://issuer.workos.test",
	"plot.workos.audience=plot-api",
	"plot.workos.jwks-uri=https://issuer.workos.test/.well-known/jwks.json",
	"plot.workos.webhook-secret=whsec_membership_test",
])
class WorkOSMembershipApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var fakeMembershipGateway: FakeWorkOSMembershipGateway

	@BeforeEach
	fun setUp() {
		deleteFixtures()
		jdbcTemplate.update(
			"""
			insert into users (id, email, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(), USER_ID, EMAIL, "Membership User",
		)
		jdbcTemplate.update(
			"""
			insert into workspaces (
			  id, name, slug, created_by_user_id, status, plan, entitlement_status, access_mode,
			  trial_started_at, trial_ends_at, public_citations_enabled, created_at, updated_at
			) values (?, 'Membership Workspace', 'membership-workspace', ?, 'ACTIVE', 'trial', 'trialing', 'full', now(), now() + interval '30 days', true, now(), now())
			""".trimIndent(), WORKSPACE_ID, USER_ID,
		)
		jdbcTemplate.update(
			"""
			insert into workspace_members (
			  id, workspace_id, user_id, role, status, joined_at, created_at, updated_at, workos_membership_id
			) values (?, ?, ?, 'MEMBER', 'ACTIVE', now(), now(), now(), ?)
			""".trimIndent(), MEMBER_ID, WORKSPACE_ID, USER_ID, MEMBERSHIP_ID,
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
		fakeMembershipGateway.current = activeSnapshot("member")
	}

	@AfterEach
	fun tearDown() = deleteFixtures()

	@Test
	fun signedMembershipEventIsProjectedAndReplayIsIgnored() {
		val body = eventBody("event-membership-updated", "organization_membership.updated", "active", "member")

		postWebhook(body).andExpect {
			status { isOk() }
			jsonPath("$.disposition") { value("PROCESSED") }
		}
		postWebhook(body).andExpect {
			status { isOk() }
			jsonPath("$.disposition") { value("DUPLICATE") }
		}

		assertEquals(
			"PROCESSED",
			jdbcTemplate.queryForObject(
				"select state from workos_membership_event_inbox where event_id = ?",
				String::class.java,
				"event-membership-updated",
			),
		)
		assertEquals(
			"MEMBER",
			jdbcTemplate.queryForObject(
				"select role from workspace_members where id = ?",
				String::class.java,
				MEMBER_ID,
			),
		)
	}

	@Test
	fun delayedGrantCannotRestoreARevokedMembership() {
		fakeMembershipGateway.current = activeSnapshot("owner")
		val body = eventBody("event-membership-created", "organization_membership.created", "active", "owner")
		fakeMembershipGateway.current = null

		postWebhook(body).andExpect {
			status { isOk() }
			jsonPath("$.disposition") { value("PROCESSED") }
		}

		assertEquals(
			"INACTIVE",
			jdbcTemplate.queryForObject(
				"select status from workspace_members where id = ?",
				String::class.java,
				MEMBER_ID,
			),
		)
	}

	@Test
	fun invalidSignatureIsRejectedBeforeInboxWrite() {
		val body = eventBody("event-invalid-signature", "organization_membership.updated", "active", "member")
		mockMvc.post("/api/workos/webhook") {
			contentType = org.springframework.http.MediaType.APPLICATION_JSON
			header("WorkOS-Signature", "t=1,v1=invalid")
			content = body
		}.andExpect { status { isBadRequest() } }

		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from workos_membership_event_inbox where event_id = ?",
				Int::class.java,
				"event-invalid-signature",
			),
		)
	}

	@Test
	fun providerFailureIsDurableAndRetryable() {
		fakeMembershipGateway.failure = RuntimeException("provider timeout")
		val body = eventBody("event-membership-retry", "organization_membership.updated", "active", "member")

		postWebhook(body).andExpect {
			status { isOk() }
			jsonPath("$.disposition") { value("RETRY") }
		}
		assertEquals(
			"RETRY",
			jdbcTemplate.queryForObject(
				"select state from workos_membership_event_inbox where event_id = ?",
				String::class.java,
				"event-membership-retry",
			),
		)
	}

	@Test
	fun scopedApiRequiresTheWorkOSOrganizationContext() {
		mockMvc.get("/api/workspaces/$WORKSPACE_ID") {
			with(jwt().jwt { token -> token.issuer("https://issuer.workos.test").subject(WORKOS_USER_ID).audience(listOf("plot-api")) })
		}.andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("WORKSPACE_CONTEXT_REQUIRED") }
		}
	}

	@Test
	fun tokenOrganizationIsTheBoundaryAndHeaderOnlyAssertsIt() {
		mockMvc.get("/api/workspaces/$WORKSPACE_ID") {
			with(jwt().jwt { token ->
				token.issuer("https://issuer.workos.test").subject(WORKOS_USER_ID).audience(listOf("plot-api"))
					.claim("org_id", ORGANIZATION_ID).claim("role", "member")
			})
		}.andExpect { status { isOk() } }

		mockMvc.get("/api/workspaces/$WORKSPACE_ID") {
			header("X-Plot-Workspace-Id", UUID.randomUUID().toString())
			with(jwt().jwt { token ->
				token.issuer("https://issuer.workos.test").subject(WORKOS_USER_ID).audience(listOf("plot-api"))
					.claim("org_id", ORGANIZATION_ID).claim("role", "member")
			})
		}.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
	}

	@Test
	fun accountDiscoveryRemainsContextFree() {
		mockMvc.get("/api/me") {
			with(jwt().jwt { token -> token.issuer("https://issuer.workos.test").subject(WORKOS_USER_ID).audience(listOf("plot-api")) })
		}.andExpect {
			status { isOk() }
			jsonPath("$.activeOrganizationId") { doesNotExist() }
		}
	}

	private fun postWebhook(body: String) = mockMvc.post("/api/workos/webhook") {
		contentType = org.springframework.http.MediaType.APPLICATION_JSON
		header("WorkOS-Signature", signature(body))
		content = body
	}

	private fun eventBody(eventId: String, event: String, status: String, role: String): String =
		"""{"id":"$eventId","event":"$event","data":{"id":"$MEMBERSHIP_ID","organization_id":"$ORGANIZATION_ID","user_id":"$WORKOS_USER_ID","status":"$status","role":{"slug":"$role"}}}"""

	private fun signature(body: String): String {
		val timestamp = Instant.now().toEpochMilli().toString()
		return "t=$timestamp,v1=${Webhook().createSignature(timestamp, body, WEBHOOK_SECRET)}"
	}

	private fun activeSnapshot(role: String) = WorkOSMembershipSnapshot(
		id = MEMBERSHIP_ID,
		organizationId = ORGANIZATION_ID,
		userId = WORKOS_USER_ID,
		status = "active",
		roleSlug = role,
	)

	private fun deleteFixtures() {
		jdbcTemplate.update("delete from workos_membership_event_inbox where workos_user_id = ?", WORKOS_USER_ID)
		jdbcTemplate.update("delete from workos_organization_mappings where workos_user_id = ?", WORKOS_USER_ID)
		jdbcTemplate.update("delete from workos_identity_mappings where workos_user_id = ?", WORKOS_USER_ID)
		jdbcTemplate.update("delete from workspace_members where id = ?", MEMBER_ID)
		jdbcTemplate.update("delete from workspaces where id = ?", WORKSPACE_ID)
		jdbcTemplate.update("delete from users where id = ?", USER_ID)
		fakeMembershipGateway.current = null
		fakeMembershipGateway.failure = null
	}

	private companion object {
		val USER_ID: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000101")
		val WORKSPACE_ID: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000102")
		val MEMBER_ID: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000103")
		const val WORKOS_USER_ID = "user_workos_membership"
		const val ORGANIZATION_ID = "org_workos_membership"
		const val MEMBERSHIP_ID = "membership_workos_membership"
		const val EMAIL = "membership@example.com"
		const val WEBHOOK_SECRET = "whsec_membership_test"
	}
}

@TestConfiguration(proxyBeanMethods = false)
class WorkOSMembershipFakeConfiguration {
	@Bean
	@Primary
	fun fakeMembershipGateway() = FakeWorkOSMembershipGateway()
}

class FakeWorkOSMembershipGateway : WorkOSMembershipGateway {
	var current: WorkOSMembershipSnapshot? = null
	var failure: RuntimeException? = null

	override fun find(organizationId: String, userId: String): WorkOSMembershipSnapshot? {
		failure?.let { throw it }
		return current?.takeIf { it.organizationId == organizationId && it.userId == userId }
	}

	override fun listForUser(userId: String): List<WorkOSMembershipSnapshot> = listOfNotNull(current?.takeIf { it.userId == userId })
}
