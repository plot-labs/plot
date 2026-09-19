package com.plot.api.billing

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class PolarClientTest {
	private val mapper = ObjectMapper()
	private val workspaceId = UUID.fromString("11111111-1111-1111-1111-111111111111")

	@Test
	fun readsConfiguredMeterBalanceFromCustomerState() {
		val client = client { method, uri, headers, _ ->
			assertEquals("GET", method)
			assertEquals("https://polar.test/v1/customers/external/plot-workspace%3A$workspaceId/state", uri.toString())
			assertEquals("Bearer polar_test_token", headers["Authorization"])
			PolarHttpResponse(200, """{"active_meters":[{"meter_id":"other","balance":99},{"meter_id":"meter_ai","balance":42}]}""")
		}

		assertEquals(42, client.readCreditBalance(workspaceId))
	}

	@Test
	fun rejectsStateWithoutTheConfiguredMeter() {
		val client = client { _, _, _, _ -> PolarHttpResponse(200, """{"active_meters":[]}""") }

		val failure = assertFailsWith<PolarApiException> { client.readCreditBalance(workspaceId) }

		assertEquals("POLAR_AI_METER_MISSING", failure.safeCode)
	}

	@Test
	fun readsCreditOverviewFromMeterStateAndPositiveUsageEvents() {
		val client = client { method, uri, _, _ ->
			when {
				method == "GET" && uri.path.endsWith("/state") -> PolarHttpResponse(
					200,
					"""{"active_meters":[{"meter_id":"meter_ai","balance":4998.0,"credited_units":5000,"consumed_units":2.0}]}""",
				)
				method == "GET" && uri.path == "/v1/events/" -> {
					assertEquals(
						"external_customer_id=plot-workspace:$workspaceId&name=plot_ai_usage&limit=100",
						uri.query,
					)
					PolarHttpResponse(
						200,
						"""
						{"items":[
							{"id":"event-usage","timestamp":"2026-09-19T12:00:00Z","metadata":{"credits":2,"provider":"openrouter","actual_model":"openai/test"}},
							{"id":"event-trial","timestamp":"2026-09-19T11:00:00Z","metadata":{"credits":-5000,"reason":"trial_grant"}}
						]}
						""".trimIndent(),
					)
				}
				else -> error("unexpected request: $method ${uri.path}")
			}
		}

		val overview = client.readCreditOverview(workspaceId)

		assertEquals(4998, overview.balance)
		assertEquals(5000, overview.creditedUnits)
		assertEquals(2, overview.consumedUnits)
		assertEquals(
			listOf(PolarCreditUsageEvent("event-usage", Instant.parse("2026-09-19T12:00:00Z"), 2, "openrouter", "openai/test")),
			overview.usageEvents,
		)
	}

	@Test
	fun createsWorkspaceCustomerAfterExternalLookupMiss() {
		val requests = mutableListOf<Pair<String, String?>>()
		val client = client { method, uri, _, body ->
			requests += uri.path to body
			when (method) {
				"GET" -> PolarHttpResponse(404, "not found")
				"POST" -> PolarHttpResponse(201, """{"id":"cus_workspace","external_id":"plot-workspace:$workspaceId"}""")
				else -> error("unexpected method")
			}
		}

		val customer = client.ensureCustomer(workspaceId, "owner@example.com", "Acme")

		assertEquals("cus_workspace", customer.id)
		val create = mapper.readTree(requests.single { it.first == "/v1/customers" }.second!!)
		assertEquals("plot-workspace:$workspaceId", create.path("external_id").stringValue())
		assertEquals("polar_org", create.path("organization_id").stringValue())
		assertEquals(workspaceId.toString(), create.path("metadata").path("workspace_id").stringValue())
		assertEquals("team", create.path("type").stringValue())
		assertEquals("owner+plot-11111111111111111111111111111111@example.com", create.path("email").stringValue())
		assertEquals("owner@example.com", create.path("owner").path("email").stringValue())
	}

	@Test
	fun rejectsLegacyCustomerThatIsNotBoundToTheWorkspace() {
		val client = client { method, uri, _, _ ->
			assertEquals("GET", method)
			assertEquals("/v1/customers/cus_legacy", uri.path)
			PolarHttpResponse(200, """{"id":"cus_legacy","external_id":"legacy-user-id"}""")
		}

		val failure = assertFailsWith<PolarApiException> {
			client.ensureCustomer(workspaceId, "owner@example.com", "Acme", "cus_legacy")
		}

		assertEquals("POLAR_CUSTOMER_MIGRATION_REQUIRED", failure.safeCode)
	}

	@Test
	fun rejectsLegacyCustomerWithoutAnExternalWorkspaceIdentity() {
		val client = client { _, _, _, _ ->
			PolarHttpResponse(200, """{"id":"cus_legacy","external_id":null}""")
		}

		val failure = assertFailsWith<PolarApiException> {
			client.ensureCustomer(workspaceId, "owner@example.com", "Acme", "cus_legacy")
		}

		assertEquals("POLAR_CUSTOMER_MIGRATION_REQUIRED", failure.safeCode)
	}

	@Test
	fun rereadsWorkspaceCustomerAfterCreateRace() {
		var lookups = 0
		val client = client { method, _, _, _ ->
			when (method) {
				"GET" -> {
					lookups++
					if (lookups == 1) PolarHttpResponse(404, "missing")
					else PolarHttpResponse(200, """{"id":"cus_existing","external_id":"plot-workspace:$workspaceId"}""")
				}
				"POST" -> PolarHttpResponse(409, "already exists")
				else -> error("unexpected method")
			}
		}

		assertEquals("cus_existing", client.ensureCustomer(workspaceId, "owner@example.com", "Acme").id)
		assertEquals(2, lookups)
	}

	@Test
	fun acceptsInsertedAndDuplicateEventsWithStableIdentity() {
		val bodies = mutableListOf<String>()
		val client = client { _, _, _, body ->
			bodies += body!!
			if (bodies.size == 1) PolarHttpResponse(200, """{"inserted":1,"duplicates":0}""")
			else PolarHttpResponse(200, """{"inserted":0,"duplicates":1}""")
		}

		val first = client.ingestCredits(workspaceId, "invocation-1", 6, mapOf("model" to "openai/test"))
		val duplicate = client.ingestCredits(workspaceId, "invocation-1", 6, mapOf("model" to "openai/test"))

		assertEquals(PolarEventResult(1, 0), first)
		assertEquals(PolarEventResult(0, 1), duplicate)
		val event = mapper.readTree(bodies.first()).path("events").first()
		assertEquals("invocation-1", event.path("external_id").stringValue())
		assertEquals("plot-workspace:$workspaceId", event.path("external_customer_id").stringValue())
		assertEquals("plot_ai_usage", event.path("name").stringValue())
		assertEquals("polar_org", event.path("organization_id").stringValue())
		assertEquals(6, event.path("metadata").path("credits").intValue())
	}

	@Test
	fun trialGrantIsNegativeAndVersionedByStableEventId() {
		var body: String? = null
		val client = client { _, _, _, requestBody ->
			body = requestBody
			PolarHttpResponse(200, """{"inserted":1,"duplicates":0}""")
		}

		client.grantTrialCredits(workspaceId)

		val event = mapper.readTree(body!!).path("events").first()
		assertEquals("trial:$workspaceId", event.path("external_id").stringValue())
		assertEquals(-5_000, event.path("metadata").path("credits").intValue())
	}

	@Test
	fun mapsUnsafeHttpFailuresToSafeErrors() {
		val client = client { _, _, _, _ -> PolarHttpResponse(401, "secret upstream response polar_test_token") }

		val failure = assertFailsWith<PolarApiException> { client.readCreditBalance(workspaceId) }

		assertEquals("POLAR_UNAUTHORIZED", failure.safeCode)
		assertFalse(failure.message.orEmpty().contains("polar_test_token"))
		assertFalse(failure.message.orEmpty().contains("secret upstream response"))
	}

	@Test
	fun rejectsMalformedIngestResponse() {
		val client = client { _, _, _, _ -> PolarHttpResponse(200, "{}") }

		val failure = assertFailsWith<PolarApiException> {
			client.ingestCredits(workspaceId, "invocation-1", 1)
		}

		assertEquals("POLAR_INVALID_RESPONSE", failure.safeCode)
	}

	private fun client(transport: PolarHttpTransport) = PolarClient(properties(), mapper, transport)

	private fun properties() = PolarProperties(
		creditsEnabled = true,
		accessToken = "polar_test_token",
		organizationId = "polar_org",
		apiBaseUrl = "https://polar.test",
		aiMeterId = "meter_ai",
		trialCredits = 5_000,
		trialPolicyVersion = "trial-v1",
		requestTimeout = Duration.ofSeconds(5),
	)
}
