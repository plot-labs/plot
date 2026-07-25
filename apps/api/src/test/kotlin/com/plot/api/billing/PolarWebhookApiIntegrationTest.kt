package com.plot.api.billing

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

private const val POLAR_TEST_WEBHOOK_SECRET = "polar_whs_integration_secret"

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.polar.enabled=true",
	"plot.polar.webhook-secret=$POLAR_TEST_WEBHOOK_SECRET",
])
class PolarWebhookApiIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var devContext: DevContext

	@BeforeEach
	fun resetBillingState() {
		jdbcTemplate.update("delete from polar_webhook_events")
		jdbcTemplate.update("delete from auth_session")
		jdbcTemplate.update(
			"""
			insert into auth_user (id, name, email, email_verified, created_at, updated_at)
			values (?, 'Dev User', 'dev@plot.local', true, now(), now())
			on conflict (id) do update set email = excluded.email, updated_at = excluded.updated_at
			""".trimIndent(),
			DEV_AUTH_SUBJECT,
		)
		jdbcTemplate.update(
			"update users set auth_issuer = ?, auth_subject = ? where id = ?",
			"https://app.useplot.xyz",
			DEV_AUTH_SUBJECT,
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			update workspaces
			set plan = 'trial',
			    entitlement_status = 'trialing',
			    access_mode = 'full',
			    trial_started_at = now(),
			    trial_ends_at = now() + interval '30 days',
			    polar_subscription_id = null,
			    polar_customer_id = null,
			    plan_updated_at = null
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
		ensureOtherUser()
	}

	@Test
	fun activeReferencePromotesWorkspaceAndExposesPlan() {
		val body = subscriptionEvent("subscription.active", "sub_active", referenceId = devContext.devUserId)

		postWebhook("msg_active", body).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "active", "full", "sub_active", "cus_active")
		assertEvent("msg_active", "PROMOTED", devContext.devUserId, devContext.devWorkspaceId)
		mockMvc.get("/api/workspaces/${devContext.devWorkspaceId}")
			.andExpect {
				status { isOk() }
				jsonPath("$.plan") { value("founding") }
				jsonPath("$.entitlementStatus") { value("active") }
				jsonPath("$.accessMode") { value("full") }
				jsonPath("$.polarSubscriptionId") { doesNotExist() }
				jsonPath("$.polarCustomerId") { doesNotExist() }
			}
	}

	@Test
	fun revokedMakesWorkspaceReadOnlyAndPreservesSessions() {
		postWebhook(
			"msg_promote",
			subscriptionEvent("subscription.active", "sub_revoke", referenceId = devContext.devUserId),
		).andExpect { status { isNoContent() } }
		insertSession("session-dev", DEV_AUTH_SUBJECT)
		insertSession("session-other", OTHER_AUTH_SUBJECT)

		postWebhook(
			"msg_revoke",
			subscriptionEvent("subscription.revoked", "sub_revoke", referenceId = devContext.devUserId),
		).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "revoked", "read_only", "sub_revoke", "cus_active")
		assertEquals(1, sessionCount(DEV_AUTH_SUBJECT))
		assertEquals(1, sessionCount(OTHER_AUTH_SUBJECT))
		assertEvent("msg_revoke", "DEMOTED", devContext.devUserId, devContext.devWorkspaceId)
	}

	@Test
	fun canceledKeepsFoundingAccess() {
		setFounding("sub_canceled")

		postWebhook(
			"msg_canceled",
			subscriptionEvent("subscription.canceled", "sub_canceled", referenceId = devContext.devUserId),
		).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "active", "full", "sub_canceled", "cus_existing")
		assertEvent("msg_canceled", "IGNORED", null, null)
	}

	@Test
	fun duplicateWebhookIdDoesNotApplyEventTwice() {
		val body = subscriptionEvent("subscription.active", "sub_duplicate", referenceId = devContext.devUserId)
		postWebhook("msg_duplicate", body).andExpect { status { isNoContent() } }
		jdbcTemplate.update(
			"update workspaces set plan = 'trial', entitlement_status = 'trialing', access_mode = 'full' where id = ?",
			devContext.devWorkspaceId,
		)

		postWebhook("msg_duplicate", body).andExpect { status { isNoContent() } }

		assertWorkspace("trial", "trialing", "full", "sub_duplicate", "cus_active")
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from polar_webhook_events where webhook_id = 'msg_duplicate'",
			Int::class.java,
		))
	}

	@Test
	fun emailFallbackPromotesWorkspace() {
		val body = subscriptionEvent("subscription.active", "sub_email")

		postWebhook("msg_email", body).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "active", "full", "sub_email", "cus_active")
		assertEvent("msg_email", "PROMOTED", devContext.devUserId, devContext.devWorkspaceId)
	}

	@Test
	fun unmatchedSubscriptionIsAcknowledgedAndAudited() {
		val body = subscriptionEvent(
			"subscription.active",
			"sub_unmatched",
			email = "unknown@example.com",
		)

		postWebhook("msg_unmatched", body).andExpect { status { isNoContent() } }

		assertWorkspace("trial", "trialing", "full", null, null)
		assertEvent("msg_unmatched", "UNMATCHED", null, null)
	}

	@Test
	fun invalidSignatureIsRejectedBeforePersistence() {
		val body = subscriptionEvent("subscription.active", "sub_invalid", referenceId = devContext.devUserId)
		val timestamp = Instant.now().epochSecond.toString()

		mockMvc.post("/api/polar/webhook") {
			contentType = MediaType.APPLICATION_JSON
			content = body
			header("webhook-id", "msg_invalid")
			header("webhook-timestamp", timestamp)
			header("webhook-signature", "v1,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
		}.andExpect {
			status { isUnauthorized() }
			jsonPath("$.error") { value("INVALID_POLAR_WEBHOOK") }
		}

		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from polar_webhook_events",
			Int::class.java,
		))
	}

	@Test
	fun revokedFromOlderSubscriptionDoesNotDemoteNewSubscription() {
		setFounding("sub_new")

		postWebhook(
			"msg_stale",
			subscriptionEvent("subscription.revoked", "sub_old", referenceId = devContext.devUserId),
		).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "active", "full", "sub_new", "cus_existing")
		assertEvent("msg_stale", "STALE_SUBSCRIPTION", devContext.devUserId, devContext.devWorkspaceId)
	}

	private fun postWebhook(webhookId: String, body: String) = Instant.now().epochSecond.toString().let { timestamp ->
		mockMvc.post("/api/polar/webhook") {
			contentType = MediaType.APPLICATION_JSON
			content = body
			header("webhook-id", webhookId)
			header("webhook-timestamp", timestamp)
			header("webhook-signature", "v1,${sign(webhookId, timestamp, body)}")
		}
	}

	private fun subscriptionEvent(
		type: String,
		subscriptionId: String,
		referenceId: UUID? = null,
		email: String = "dev@plot.local",
	): String {
		val metadata = referenceId?.let { """"reference_id":"$it"""" }.orEmpty()
		return """
			{"type":"$type","data":{"id":"$subscriptionId","metadata":{$metadata},"customer":{"id":"cus_active","external_id":null,"email":"$email"}}}
		""".trimIndent()
	}

	private fun sign(webhookId: String, timestamp: String, body: String): String {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(POLAR_TEST_WEBHOOK_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
		val bytes = mac.doFinal("$webhookId.$timestamp.$body".toByteArray(StandardCharsets.UTF_8))
		return Base64.getEncoder().encodeToString(bytes)
	}

	private fun setFounding(subscriptionId: String) {
		jdbcTemplate.update(
			"""
			update workspaces
			set plan = 'founding',
			    entitlement_status = 'active',
			    access_mode = 'full',
			    polar_subscription_id = ?,
			    polar_customer_id = 'cus_existing',
			    plan_updated_at = now()
			where id = ?
			""".trimIndent(),
			subscriptionId,
			devContext.devWorkspaceId,
		)
	}

	private fun assertWorkspace(
		plan: String,
		entitlementStatus: String,
		accessMode: String,
		subscriptionId: String?,
		customerId: String?,
	) {
		val row = jdbcTemplate.queryForMap(
			"""
			select plan, entitlement_status, access_mode,
			       polar_subscription_id, polar_customer_id, plan_updated_at
			from workspaces
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
		assertEquals(plan, row["plan"])
		assertEquals(entitlementStatus, row["entitlement_status"])
		assertEquals(accessMode, row["access_mode"])
		assertEquals(subscriptionId, row["polar_subscription_id"])
		assertEquals(customerId, row["polar_customer_id"])
		if (plan == "founding" || subscriptionId != null) assertNotNull(row["plan_updated_at"])
	}

	private fun assertEvent(
		webhookId: String,
		outcome: String,
		userId: UUID?,
		workspaceId: UUID?,
	) {
		val row = jdbcTemplate.queryForMap(
			"select outcome, matched_user_id, matched_workspace_id from polar_webhook_events where webhook_id = ?",
			webhookId,
		)
		assertEquals(outcome, row["outcome"])
		assertEquals(userId, row["matched_user_id"])
		assertEquals(workspaceId, row["matched_workspace_id"])
	}

	private fun insertSession(id: String, authSubject: String) {
		jdbcTemplate.update(
			"""
			insert into auth_session (
			  id, expires_at, token, created_at, updated_at, user_id
			) values (?, now() + interval '1 day', ?, now(), now(), ?)
			""".trimIndent(),
			id,
			"token-$id",
			authSubject,
		)
	}

	private fun sessionCount(authSubject: String): Int = jdbcTemplate.queryForObject(
		"select count(*) from auth_session where user_id = ?",
		Int::class.java,
		authSubject,
	) ?: 0

	private fun ensureOtherUser() {
		jdbcTemplate.update(
			"""
			insert into auth_user (id, name, email, email_verified, created_at, updated_at)
			values (?, 'Other User', 'other@plot.local', true, now(), now())
			on conflict (id) do update set email = excluded.email, updated_at = excluded.updated_at
			""".trimIndent(),
			OTHER_AUTH_SUBJECT,
		)
		jdbcTemplate.update(
			"""
			insert into users (
			  id, email, display_name, status, auth_issuer, auth_subject, created_at, updated_at
			) values (?, 'other@plot.local', 'Other User', 'ACTIVE', ?, ?, now(), now())
			on conflict (id) do update set auth_subject = excluded.auth_subject, updated_at = excluded.updated_at
			""".trimIndent(),
			OTHER_USER_ID,
			"https://app.useplot.xyz",
			OTHER_AUTH_SUBJECT,
		)
	}

	private companion object {
		const val DEV_AUTH_SUBJECT = "auth-dev"
		const val OTHER_AUTH_SUBJECT = "auth-other"
		val OTHER_USER_ID: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000099")
	}
}
