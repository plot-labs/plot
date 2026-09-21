package com.plot.api.billing

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, PolarSubscriptionPortalApiIntegrationTest.Config::class)
@ActiveProfiles("test")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.polar.credits-enabled=true",
	"plot.polar.access-token=polar_test_token",
	"plot.polar.ai-meter-id=meter_ai",
	"plot.polar.subscription-product-id=product_subscription",
		"plot.polar.checkout-success-url=http://localhost:3000/settings/general?checkout_id={CHECKOUT_ID}",
		"plot.polar.checkout-return-url=http://localhost:3000/settings/general",
])
class PolarSubscriptionPortalApiIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var transport: FakePolarHttpTransport

	@Autowired
	private lateinit var objectMapper: ObjectMapper

	@BeforeEach
	fun resetWorkspace() {
		transport.reset()
		jdbcTemplate.update(
			"""
			update workspaces
			set plan = 'founding',
			    entitlement_status = 'active',
			    access_mode = 'full',
			    polar_subscription_id = 'sub_portal',
			    polar_customer_id = null,
			    polar_subscription_status = 'active',
			    polar_subscription_cancel_at_period_end = false,
			    polar_subscription_current_period_end = '2026-10-21T00:00:00Z',
			    polar_subscription_event_at = '2026-09-21T00:00:00Z'
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun ownerReceivesOnlyTheOneTimePortalUrlForItsWorkspace() {
		mockMvc.post("/api/billing/subscription-portal") {
			contentType = MediaType.APPLICATION_JSON
		}.andExpect {
			status { isCreated() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.url") { value("https://sandbox.polar.sh/customer-portal/session-1") }
			jsonPath("$.token") { doesNotExist() }
		}

		val sessionRequest = transport.calls.single { it.uri.path == "/v1/customer-sessions" }
		val request = objectMapper.readTree(requireNotNull(sessionRequest.body))
		assertEquals("plot-workspace:${devContext.devWorkspaceId}", request.path("external_customer_id").stringValue())
		assertEquals("http://localhost:3000/settings/general", request.path("return_url").stringValue())
		assertFalse(transport.calls.any { it.body?.contains("secret-session-token") == true })
	}

	@Test
	fun activeSubscriptionCannotStartADuplicateCheckout() {
		mockMvc.post("/api/billing/subscription-checkout") {
			contentType = MediaType.APPLICATION_JSON
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("SUBSCRIPTION_MANAGEMENT_REQUIRED") }
		}

		assertFalse(transport.calls.any { it.uri.path == "/v1/checkouts/" })
	}

	@Test
	fun revokedWorkspaceCanStartASubscriptionCheckoutAgain() {
		jdbcTemplate.update(
			"update workspaces set entitlement_status = 'revoked', access_mode = 'read_only' where id = ?",
			devContext.devWorkspaceId,
		)

		mockMvc.post("/api/billing/subscription-checkout") {
			contentType = MediaType.APPLICATION_JSON
		}.andExpect {
			status { isCreated() }
			jsonPath("$.checkoutId") { value("checkout-resubscribe") }
			jsonPath("$.url") { value("https://sandbox.polar.sh/checkout/checkout-resubscribe") }
		}

		assertEquals(1, transport.calls.count { it.uri.path == "/v1/checkouts/" })
	}

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun fakePolarHttpTransport() = FakePolarHttpTransport()
	}
}

class FakePolarHttpTransport : PolarHttpTransport {
	data class Call(val method: String, val uri: URI, val body: String?)

	val calls = CopyOnWriteArrayList<Call>()

	fun reset() {
		calls.clear()
	}

	override fun execute(method: String, uri: URI, headers: Map<String, String>, body: String?): PolarHttpResponse {
		calls += Call(method, uri, body)
		return when {
			uri.path.startsWith("/v1/customers/external/") || uri.path == "/v1/customers/cus_portal" -> PolarHttpResponse(
				200,
				"""{"id":"cus_portal","external_id":"plot-workspace:018fd000-0000-7000-8000-000000000002"}""",
			)
			uri.path == "/v1/customer-sessions" -> PolarHttpResponse(
				201,
				"""{"customer_portal_url":"https://sandbox.polar.sh/customer-portal/session-1","token":"secret-session-token"}""",
			)
			uri.path == "/v1/checkouts/" -> PolarHttpResponse(
				201,
				"""{"id":"checkout-resubscribe","url":"https://sandbox.polar.sh/checkout/checkout-resubscribe"}""",
			)
			else -> error("Unexpected Polar request: ${uri.path}")
		}
	}
}
