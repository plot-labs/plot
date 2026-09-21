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

private const val POLAR_TEST_WEBHOOK_SECRET = "whsec_c2VjcmV0"

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
			    polar_subscription_status = null,
			    polar_subscription_cancel_at_period_end = null,
			    polar_subscription_current_period_end = null,
			    polar_subscription_event_at = null,
			    plan_updated_at = null
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun workspaceReferencePromotesThatWorkspaceAndExposesPlan() {
		val body = subscriptionEvent("subscription.active", "sub_active", referenceId = devContext.devWorkspaceId)

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
	fun activeWebhookExposesSubscriptionLifecycleSnapshot() {
		val periodEnd = "2026-10-21T00:00:00Z"
		val eventAt = "2026-09-21T00:00:00Z"
		val body = subscriptionEvent(
			"subscription.active",
			"sub_snapshot",
			referenceId = devContext.devWorkspaceId,
			currentPeriodEnd = periodEnd,
			eventAt = eventAt,
		)

		postWebhook("msg_snapshot", body).andExpect { status { isNoContent() } }

		mockMvc.get("/api/workspaces/${devContext.devWorkspaceId}")
			.andExpect {
				status { isOk() }
				jsonPath("$.subscriptionStatus") { value("active") }
				jsonPath("$.subscriptionCancelAtPeriodEnd") { value(false) }
				jsonPath("$.subscriptionCurrentPeriodEnd") { value(periodEnd) }
				jsonPath("$.subscriptionEventAt") { value(eventAt) }
			}
	}

	@Test
	fun revokedMakesWorkspaceReadOnly() {
		postWebhook(
			"msg_promote",
			subscriptionEvent("subscription.active", "sub_revoke", referenceId = devContext.devUserId, eventAt = "2026-09-21T01:00:00Z"),
		).andExpect { status { isNoContent() } }

		postWebhook(
			"msg_revoke",
			subscriptionEvent("subscription.revoked", "sub_revoke", referenceId = devContext.devUserId, eventAt = "2026-09-21T02:00:00Z"),
		).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "revoked", "read_only", "sub_revoke", "cus_active")
		assertEvent("msg_revoke", "DEMOTED", devContext.devUserId, devContext.devWorkspaceId)
	}

	@Test
	fun newActiveSubscriptionRestoresARevokedWorkspace() {
		postWebhook(
			"msg_restore_active",
			subscriptionEvent("subscription.active", "sub_before_restore", referenceId = devContext.devWorkspaceId, eventAt = "2026-09-21T01:00:00Z"),
		).andExpect { status { isNoContent() } }
		postWebhook(
			"msg_restore_revoke",
			subscriptionEvent("subscription.revoked", "sub_before_restore", referenceId = devContext.devWorkspaceId, eventAt = "2026-09-21T02:00:00Z"),
		).andExpect { status { isNoContent() } }
		postWebhook(
			"msg_restore_new_active",
			subscriptionEvent("subscription.active", "sub_after_restore", referenceId = devContext.devWorkspaceId, eventAt = "2026-09-21T03:00:00Z"),
		).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "active", "full", "sub_after_restore", "cus_active")
		assertLifecycle("active", false, "2026-10-21T00:00:00Z", "2026-09-21T03:00:00Z")
	}

	@Test
	fun canceledKeepsFoundingAccess() {
		setFounding("sub_canceled")

		postWebhook(
			"msg_canceled",
			subscriptionEvent("subscription.canceled", "sub_canceled", referenceId = devContext.devUserId),
		).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "active", "full", "sub_canceled", "cus_existing")
		assertLifecycle("canceled", false, null, "2026-09-21T00:00:00Z")
		assertEvent("msg_canceled", "SNAPSHOT_UPDATED", devContext.devUserId, devContext.devWorkspaceId)
	}

	@Test
	fun periodEndCancellationKeepsFullAccessAndRecordsTheSchedule() {
		postWebhook(
			"msg_period_active",
			subscriptionEvent("subscription.active", "sub_period", referenceId = devContext.devWorkspaceId, eventAt = "2026-09-21T01:00:00Z"),
		).andExpect { status { isNoContent() } }

		postWebhook(
			"msg_period_cancel",
			subscriptionEvent(
				"subscription.canceled",
				"sub_period",
				referenceId = devContext.devWorkspaceId,
				cancelAtPeriodEnd = true,
				currentPeriodEnd = "2026-10-31T00:00:00Z",
				eventAt = "2026-09-21T02:00:00Z",
			),
		).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "active", "full", "sub_period", "cus_active")
		assertLifecycle("canceled", true, "2026-10-31T00:00:00Z", "2026-09-21T02:00:00Z")

		postWebhook(
			"msg_period_uncancel",
			subscriptionEvent("subscription.uncanceled", "sub_period", referenceId = devContext.devWorkspaceId, eventAt = "2026-09-21T03:00:00Z"),
		).andExpect { status { isNoContent() } }
		assertLifecycle("active", false, "2026-10-21T00:00:00Z", "2026-09-21T03:00:00Z")
	}

	@Test
	fun pastDueAndUncanceledEventsKeepAccessAndActiveClearsBillingState() {
		postWebhook(
			"msg_recovery_active",
			subscriptionEvent("subscription.active", "sub_recovery", referenceId = devContext.devWorkspaceId, eventAt = "2026-09-21T01:00:00Z"),
		).andExpect { status { isNoContent() } }
		postWebhook(
			"msg_past_due",
			subscriptionEvent("subscription.past_due", "sub_recovery", referenceId = devContext.devWorkspaceId, eventAt = "2026-09-21T02:00:00Z"),
		).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "active", "full", "sub_recovery", "cus_active")
		assertLifecycle("past_due", false, "2026-10-21T00:00:00Z", "2026-09-21T02:00:00Z")

		postWebhook(
			"msg_recovered",
			subscriptionEvent("subscription.active", "sub_recovery", referenceId = devContext.devWorkspaceId, eventAt = "2026-09-21T03:00:00Z"),
		).andExpect { status { isNoContent() } }

		assertLifecycle("active", false, "2026-10-21T00:00:00Z", "2026-09-21T03:00:00Z")
	}

	@Test
	fun updatedOnlyRefreshesTheCurrentSubscriptionSnapshot() {
		postWebhook(
			"msg_updated_active",
			subscriptionEvent("subscription.active", "sub_updated", referenceId = devContext.devWorkspaceId, eventAt = "2026-09-21T01:00:00Z"),
		).andExpect { status { isNoContent() } }

		postWebhook(
			"msg_updated",
			subscriptionEvent(
				"subscription.updated",
				"sub_updated",
				referenceId = devContext.devWorkspaceId,
				status = "active",
				cancelAtPeriodEnd = true,
				currentPeriodEnd = "2026-11-21T00:00:00Z",
				eventAt = "2026-09-21T02:00:00Z",
			),
		).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "active", "full", "sub_updated", "cus_active")
		assertLifecycle("active", true, "2026-11-21T00:00:00Z", "2026-09-21T02:00:00Z")
	}

	@Test
	fun staleLifecycleEventAndInvalidTimestampCannotOverwriteSnapshot() {
		postWebhook(
			"msg_latest_active",
			subscriptionEvent("subscription.active", "sub_ordered", referenceId = devContext.devWorkspaceId, eventAt = "2026-09-21T02:00:00Z"),
		).andExpect { status { isNoContent() } }

		postWebhook(
			"msg_stale_cancel",
			subscriptionEvent(
				"subscription.canceled",
				"sub_ordered",
				referenceId = devContext.devWorkspaceId,
				cancelAtPeriodEnd = true,
				eventAt = "2026-09-21T01:00:00Z",
			),
		).andExpect { status { isNoContent() } }
		assertEvent("msg_stale_cancel", "STALE_EVENT", devContext.devUserId, devContext.devWorkspaceId)

		postWebhook(
			"msg_invalid_time",
			subscriptionEvent("subscription.past_due", "sub_ordered", referenceId = devContext.devWorkspaceId, eventAt = "invalid"),
		).andExpect { status { isNoContent() } }
		assertEvent("msg_invalid_time", "INVALID_TIMESTAMP", devContext.devUserId, devContext.devWorkspaceId)
		assertLifecycle("active", false, "2026-10-21T00:00:00Z", "2026-09-21T02:00:00Z")
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

	@Test
	fun workspaceExternalCustomerPromotesTheWorkspaceFromPolarCheckout() {
		val body = subscriptionEvent(
			"subscription.active",
			"sub_external_workspace",
			externalCustomerId = "plot-workspace:${devContext.devWorkspaceId}",
		)

		postWebhook("msg_external_workspace", body).andExpect { status { isNoContent() } }

		assertWorkspace("founding", "active", "full", "sub_external_workspace", "cus_active")
		assertEvent("msg_external_workspace", "PROMOTED", devContext.devUserId, devContext.devWorkspaceId)
	}

	private fun subscriptionEvent(
		type: String,
		subscriptionId: String,
		referenceId: UUID? = null,
		externalCustomerId: String? = null,
		email: String = "dev@plot.local",
		currentPeriodEnd: String = "2026-10-21T00:00:00Z",
		eventAt: String = "2026-09-21T00:00:00Z",
		status: String? = null,
		cancelAtPeriodEnd: Boolean? = null,
	): String {
		val metadata = referenceId?.let { """"reference_id":"$it"""" }.orEmpty()
		val externalId = externalCustomerId?.let { """"$it"""" } ?: "null"
		val subscriptionStatus = status?.let { ",\"status\":\"$it\"" }.orEmpty()
		val cancellation = cancelAtPeriodEnd?.let { ",\"cancel_at_period_end\":$it" }.orEmpty()
		return """
			{"type":"$type","timestamp":"$eventAt","data":{"id":"$subscriptionId","current_period_end":"$currentPeriodEnd"$subscriptionStatus$cancellation,"metadata":{$metadata},"customer":{"id":"cus_active","external_id":$externalId,"email":"$email"}}}
		""".trimIndent()
	}

	private fun sign(webhookId: String, timestamp: String, body: String): String {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(
			Base64.getDecoder().decode(POLAR_TEST_WEBHOOK_SECRET.removePrefix("whsec_")),
			"HmacSHA256",
		))
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

	private fun assertLifecycle(
		status: String?,
		cancelAtPeriodEnd: Boolean?,
		currentPeriodEnd: String?,
		eventAt: String,
	) {
		val row = jdbcTemplate.queryForMap(
			"""
			select polar_subscription_status, polar_subscription_cancel_at_period_end,
			       polar_subscription_current_period_end, polar_subscription_event_at
			from workspaces
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
		assertEquals(status, row["polar_subscription_status"])
		assertEquals(cancelAtPeriodEnd, row["polar_subscription_cancel_at_period_end"])
		assertEquals(currentPeriodEnd?.let(Instant::parse), (row["polar_subscription_current_period_end"] as? java.sql.Timestamp)?.toInstant())
		assertEquals(Instant.parse(eventAt), (row["polar_subscription_event_at"] as? java.sql.Timestamp)?.toInstant())
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

}
