package com.plot.api.billing

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class PolarCustomer(
	val id: String,
	val externalId: String,
	val type: String = "individual",
)

data class PolarCheckoutSession(val id: String, val url: String)

data class PolarCustomerPortal(val url: String)

data class PolarCreditUsageEvent(
	val id: String,
	val timestamp: Instant,
	val credits: Long,
	val provider: String?,
	val model: String?,
)

data class PolarCreditOverview(
	val balance: Long,
	val creditedUnits: Long,
	val consumedUnits: Long,
	val usageEvents: List<PolarCreditUsageEvent>,
) {
	companion object {
		fun empty() = PolarCreditOverview(0, 0, 0, emptyList())
	}
}

data class PolarEventResult(val inserted: Int, val duplicates: Int) {
	init {
		require(inserted >= 0 && duplicates >= 0)
		require(inserted + duplicates > 0)
	}
}

class PolarApiException(
	val safeCode: String,
	message: String,
	val retryable: Boolean = false,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

data class PolarHttpResponse(val status: Int, val body: String)

interface PolarCreditProvider {
	fun ensureCustomer(
		workspaceId: UUID,
		ownerEmail: String,
		workspaceName: String,
		existingCustomerId: String? = null,
	): PolarCustomer
	fun readCreditBalance(workspaceId: UUID): Long
	fun readCreditOverview(workspaceId: UUID): PolarCreditOverview
	fun ingestCredits(workspaceId: UUID, eventId: String, credits: Long, metadata: Map<String, Any> = emptyMap()): PolarEventResult
}

interface PolarCheckoutProvider {
	fun createCheckoutSession(
		workspaceId: UUID,
		productId: String,
		customerName: String,
		customerEmail: String,
		successUrl: String,
		returnUrl: String?,
		purpose: String = "credit_top_up",
	): PolarCheckoutSession
}

interface PolarCustomerPortalProvider {
	fun createCustomerPortalSession(workspaceId: UUID, returnUrl: String): PolarCustomerPortal
}

fun interface PolarHttpTransport {
	fun execute(method: String, uri: URI, headers: Map<String, String>, body: String?): PolarHttpResponse
}

@Component
class JavaPolarHttpTransport(private val properties: PolarProperties) : PolarHttpTransport {
	private val client = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(properties.requestTimeout)
		.build()

	override fun execute(method: String, uri: URI, headers: Map<String, String>, body: String?): PolarHttpResponse {
		val builder = HttpRequest.newBuilder(uri)
			.timeout(properties.requestTimeout)
			.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
		headers.forEach(builder::header)
		val response = try {
			client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw exception
		}
		return PolarHttpResponse(response.statusCode(), readCapped(response.body()))
	}

	private fun readCapped(stream: java.io.InputStream): String {
		val buffer = ByteArrayOutputStream(minOf(properties.maxResponseBytes, 64 * 1024))
		val chunk = ByteArray(8_192)
		stream.use { input ->
			while (true) {
				val read = input.read(chunk)
				if (read < 0) break
				if (buffer.size() + read > properties.maxResponseBytes) {
					throw PolarApiException("POLAR_RESPONSE_TOO_LARGE", "Polar response exceeds the allowed size")
				}
				buffer.write(chunk, 0, read)
			}
		}
		return buffer.toString(StandardCharsets.UTF_8)
	}
}

@Component
class PolarClient(
	private val properties: PolarProperties,
	private val objectMapper: ObjectMapper,
	private val transport: PolarHttpTransport = JavaPolarHttpTransport(properties),
) : PolarCreditProvider, PolarCheckoutProvider, PolarCustomerPortalProvider {
	fun workspaceExternalId(workspaceId: UUID): String = "plot-workspace:$workspaceId"

	override fun ensureCustomer(
		workspaceId: UUID,
		ownerEmail: String,
		workspaceName: String,
		existingCustomerId: String?,
	): PolarCustomer {
		ensureEnabled()
		val externalId = workspaceExternalId(workspaceId)
		existingCustomerId?.let { customerId ->
			findCustomerById(customerId)?.let { return it.requireWorkspaceIdentity(externalId) }
		}
		findCustomer(externalId)?.let { return it }
		val payload = mapOf(
			"external_id" to externalId,
			"email" to workspaceCustomerEmail(ownerEmail, workspaceId),
			"name" to workspaceName,
			"type" to "individual",
			"metadata" to mapOf("workspace_id" to workspaceId.toString()),
		)
		val response = execute("POST", "/v1/customers", objectMapper.writeValueAsString(payload), accepted = setOf(201, 409))
		if (response.status == 409) {
			return findCustomer(externalId)
				?: throw PolarApiException("POLAR_CUSTOMER_CONFLICT", "Polar customer ownership could not be confirmed")
		}
		return parseCustomer(response.body, externalId)
	}

	override fun readCreditBalance(workspaceId: UUID): Long {
		return requireNotNull(readMeterState(workspaceId)).balance
	}

	override fun readCreditOverview(workspaceId: UUID): PolarCreditOverview {
		ensureEnabled()
		val meter = readMeterState(workspaceId, allowMissingCustomer = true) ?: return PolarCreditOverview.empty()
		val externalId = workspaceExternalId(workspaceId)
		var page = 1
		var maxPage = 1
		var consumedUnits = 0L
		var eventCreditedUnits = 0L
		val recentUsageEvents = mutableListOf<PolarCreditUsageEvent>()
		do {
			val query = listOf(
				"external_customer_id=${segment(externalId)}",
				"name=${segment(AI_USAGE_EVENT)}",
				"limit=$EVENT_PAGE_SIZE",
				"page=$page",
				"sorting=-timestamp",
			).joinToString("&")
			val response = execute(
				"GET",
				"/v1/events/?$query",
			)
			val root = parse(response.body)
			val items = root.path("items")
			if (!items.isArray) invalidResponse()
			val ledgerEvents = items.mapNotNull { event -> parseUsageEvent(event) }
			consumedUnits = consumedUnits.checkedAdd(sumPositiveCredits(ledgerEvents))
			eventCreditedUnits = eventCreditedUnits.checkedAdd(sumNegativeCredits(ledgerEvents))
			if (page == 1) recentUsageEvents += ledgerEvents.filter { it.credits > 0 }
			if (page == 1) {
				val reportedMaxPage = root.path("pagination").path("max_page").exactLongOrNull() ?: invalidResponse()
				if (reportedMaxPage !in 0..Int.MAX_VALUE.toLong()) invalidResponse()
				if (reportedMaxPage == 0L && items.size() > 0) invalidResponse()
				maxPage = reportedMaxPage.toInt()
			}
			page++
		} while (page <= maxPage)
		return PolarCreditOverview(
			balance = meter.balance,
			creditedUnits = maxOf(
				meter.creditedUnits,
				eventCreditedUnits,
				meter.balance.checkedAdd(consumedUnits),
			),
			consumedUnits = consumedUnits,
			usageEvents = recentUsageEvents,
		)
	}

	override fun ingestCredits(
		workspaceId: UUID,
		eventId: String,
		credits: Long,
		metadata: Map<String, Any>,
	): PolarEventResult {
		require(credits > 0) { "Usage credits must be positive" }
		return ingest(workspaceId, eventId, credits, metadata)
	}

	override fun createCheckoutSession(
		workspaceId: UUID,
		productId: String,
		customerName: String,
		customerEmail: String,
		successUrl: String,
		returnUrl: String?,
		purpose: String,
	): PolarCheckoutSession {
		ensureEnabled()
		val payload = linkedMapOf<String, Any>(
			"products" to listOf(productId),
			"external_customer_id" to workspaceExternalId(workspaceId),
			"customer_name" to customerName,
			"customer_email" to customerEmail,
			"success_url" to successUrl,
			"metadata" to mapOf(
				"workspace_id" to workspaceId.toString(),
				"reference_id" to workspaceId.toString(),
				"purpose" to purpose,
			),
		).apply {
			returnUrl?.takeIf(String::isNotBlank)?.let { put("return_url", it) }
		}
		val response = execute(
			"POST",
			"/v1/checkouts/",
			objectMapper.writeValueAsString(payload),
			accepted = setOf(201),
		)
		val root = parse(response.body)
		val id = root.path("id").stringValue()?.takeIf(String::isNotBlank) ?: invalidResponse()
		val url = root.path("url").stringValue()?.takeIf(String::isNotBlank) ?: invalidResponse()
		return PolarCheckoutSession(id, url)
	}

	override fun createCustomerPortalSession(workspaceId: UUID, returnUrl: String): PolarCustomerPortal {
		ensureEnabled()
		val externalId = workspaceExternalId(workspaceId)
		val customer = findCustomer(externalId)
			?: throw PolarApiException("POLAR_NOT_FOUND", "Polar customer was not found")
		val payload = mapOf(
			"external_customer_id" to externalId,
			"return_url" to returnUrl,
		).toMutableMap().apply {
			// Legacy workspace customers were created as teams. Polar requires the
			// member identity for their portal sessions; Plot binds that member to the
			// same stable workspace external ID when it creates the customer.
			if (customer.type == "team") put("external_member_id", externalId)
		}
		val response = execute(
			"POST",
			"/v1/customer-sessions/",
			objectMapper.writeValueAsString(payload),
			accepted = setOf(201),
		)
		val url = parse(response.body).path("customer_portal_url").stringValue()?.takeIf(String::isNotBlank)
			?: invalidResponse()
		val portalUri = runCatching { URI.create(url) }.getOrElse { invalidResponse() }
		if (portalUri.scheme != "https" || portalUri.host.isNullOrBlank()) invalidResponse()
		return PolarCustomerPortal(url)
	}

	private fun ingest(
		workspaceId: UUID,
		eventId: String,
		credits: Long,
		metadata: Map<String, Any>,
	): PolarEventResult {
		ensureEnabled()
		val eventMetadata = linkedMapOf<String, Any>("credits" to credits).apply { putAll(metadata) }
		val payload = mapOf(
			"events" to listOf(
				mapOf(
					"external_id" to eventId,
					"name" to AI_USAGE_EVENT,
					"external_customer_id" to workspaceExternalId(workspaceId),
					"timestamp" to Instant.now().toString(),
					"metadata" to eventMetadata,
				),
			),
		)
		val response = execute("POST", "/v1/events/ingest", objectMapper.writeValueAsString(payload))
		val root = parse(response.body)
		if (!root.path("inserted").isIntegralNumber || !root.path("duplicates").isIntegralNumber) invalidResponse()
		return try {
			PolarEventResult(root.path("inserted").intValue(), root.path("duplicates").intValue())
		} catch (_: IllegalArgumentException) {
			invalidResponse()
		}
	}

	private fun readMeterState(workspaceId: UUID, allowMissingCustomer: Boolean = false): PolarMeterState? {
		ensureEnabled()
		val externalId = workspaceExternalId(workspaceId)
		val response = execute(
			"GET",
			"/v1/customers/external/${segment(externalId)}/state",
			accepted = if (allowMissingCustomer) setOf(200, 404) else setOf(200),
		)
		if (response.status == 404) return null
		val root = parse(response.body)
		val meters = root.path("active_meters")
		if (!meters.isArray) invalidResponse()
		val meter = meters.firstOrNull { it.path("meter_id").stringValue() == properties.aiMeterId }
			?: throw PolarApiException("POLAR_AI_METER_MISSING", "Polar AI credit meter is unavailable")
		val balance = meter.path("balance").exactLong()
		return PolarMeterState(
			balance = balance,
			creditedUnits = meter.path("credited_units").exactLongOrNull() ?: balance,
			consumedUnits = meter.path("consumed_units").exactLongOrNull() ?: 0,
		)
	}

	private fun parseUsageEvent(event: JsonNode): PolarCreditUsageEvent? {
		val metadata = event.path("metadata")
		val credits = metadata.path("credits").exactLongOrNull() ?: return null
		val id = event.path("id").textOrNull()?.takeIf(String::isNotBlank) ?: return null
		val timestamp = event.path("timestamp").textOrNull()?.let { value ->
			runCatching { Instant.parse(value) }.getOrNull()
		} ?: return null
		return PolarCreditUsageEvent(
			id = id,
			timestamp = timestamp,
			credits = credits,
			provider = metadata.path("provider").textOrNull()?.takeIf(String::isNotBlank),
			model = listOf("actual_model", "requested_model")
				.asSequence()
				.mapNotNull { metadata.path(it).textOrNull()?.takeIf(String::isNotBlank) }
				.firstOrNull(),
		)
	}

	private fun sumPositiveCredits(events: List<PolarCreditUsageEvent>): Long = events
		.asSequence()
		.filter { it.credits > 0 }
		.fold(0L) { total, event -> total.checkedAdd(event.credits) }

	private fun sumNegativeCredits(events: List<PolarCreditUsageEvent>): Long = events
		.asSequence()
		.filter { it.credits < 0 }
		.fold(0L) { total, event -> total.checkedAdd(event.credits.checkedNegate()) }

	private fun findCustomer(externalId: String): PolarCustomer? {
		val response = execute(
			"GET",
			"/v1/customers/external/${segment(externalId)}",
			accepted = setOf(200, 404),
		)
		if (response.status == 404) return null
		return parseCustomer(response.body, externalId)
	}

	private fun findCustomerById(customerId: String): PolarCustomer? {
		val response = execute(
			"GET",
			"/v1/customers/${segment(customerId)}",
			accepted = setOf(200, 404),
		)
		if (response.status == 404) return null
		val root = parse(response.body)
		val id = root.path("id").stringValue()?.takeIf(String::isNotBlank) ?: invalidResponse()
		val externalId = root.path("external_id").stringValue().orEmpty()
		val type = root.path("type").textOrNull()?.lowercase() ?: "individual"
		return PolarCustomer(id, externalId, type)
	}

	private fun parseCustomer(body: String, expectedExternalId: String): PolarCustomer {
		val root = parse(body)
		val id = root.path("id").stringValue()?.takeIf(String::isNotBlank) ?: invalidResponse()
		val externalId = root.path("external_id").stringValue()?.takeIf(String::isNotBlank) ?: invalidResponse()
		if (externalId != expectedExternalId) {
			throw PolarApiException("POLAR_CUSTOMER_OWNERSHIP_MISMATCH", "Polar customer does not belong to this workspace")
		}
		val type = root.path("type").textOrNull()?.lowercase() ?: "individual"
		return PolarCustomer(id, externalId, type)
	}

	private fun PolarCustomer.requireWorkspaceIdentity(expectedExternalId: String): PolarCustomer {
		if (externalId != expectedExternalId) {
			throw PolarApiException(
				"POLAR_CUSTOMER_MIGRATION_REQUIRED",
				"Existing Polar customer is not bound to this workspace",
			)
		}
		return this
	}

	private fun workspaceCustomerEmail(ownerEmail: String, workspaceId: UUID): String {
		val separator = ownerEmail.lastIndexOf('@')
		if (separator <= 0 || separator == ownerEmail.lastIndex) {
			throw PolarApiException("POLAR_CUSTOMER_EMAIL_INVALID", "Workspace owner email is invalid")
		}
		val mailbox = ownerEmail.substring(0, separator).substringBefore('+').take(20)
		val domain = ownerEmail.substring(separator + 1)
		return "$mailbox+plot-${workspaceId.toString().replace("-", "")}@$domain"
	}

	private fun execute(
		method: String,
		path: String,
		body: String? = null,
		accepted: Set<Int> = setOf(200),
	): PolarHttpResponse {
		val response = try {
			transport.execute(
				method,
				URI.create(properties.apiBaseUrl.trimEnd('/') + path),
				mapOf(
					"Authorization" to "Bearer ${properties.accessToken}",
					"Accept" to "application/json",
					"Content-Type" to "application/json",
				),
				body,
			)
		} catch (exception: PolarApiException) {
			throw exception
		} catch (exception: RuntimeException) {
			throw PolarApiException("POLAR_UNAVAILABLE", "Polar is unavailable", retryable = true, cause = exception)
		} catch (exception: java.io.IOException) {
			throw PolarApiException("POLAR_UNAVAILABLE", "Polar is unavailable", retryable = true, cause = exception)
		}
		if (response.status !in accepted) throw statusFailure(response.status)
		return response
	}

	private fun parse(body: String): JsonNode = try {
		objectMapper.readTree(body)
	} catch (_: RuntimeException) {
		invalidResponse()
	}

	private fun ensureEnabled() {
		if (!properties.creditsEnabled) throw PolarApiException("POLAR_CREDITS_DISABLED", "Polar credits are disabled")
	}

	private fun statusFailure(status: Int): PolarApiException = when (status) {
		401, 403 -> PolarApiException("POLAR_UNAUTHORIZED", "Polar authorization failed")
		404 -> PolarApiException("POLAR_NOT_FOUND", "Polar resource was not found")
		409 -> PolarApiException("POLAR_CONFLICT", "Polar resource conflicts with existing state")
		422 -> PolarApiException("POLAR_INVALID_REQUEST", "Polar rejected the request")
		429 -> PolarApiException("POLAR_RATE_LIMITED", "Polar is temporarily unavailable", retryable = true)
		in 500..599 -> PolarApiException("POLAR_UNAVAILABLE", "Polar is unavailable", retryable = true)
		else -> PolarApiException("POLAR_REQUEST_FAILED", "Polar request failed")
	}

	private fun invalidResponse(): Nothing = throw PolarApiException(
		"POLAR_INVALID_RESPONSE",
		"Polar returned an invalid response",
	)

	private fun JsonNode.exactLong(): Long = exactLongOrNull() ?: invalidResponse()

	private fun JsonNode.exactLongOrNull(): Long? {
		if (isMissingNode || isNull) return null
		return try {
			decimalValue().longValueExact()
		} catch (_: RuntimeException) {
			invalidResponse()
		}
	}

	private fun Long.checkedAdd(other: Long): Long = try {
		Math.addExact(this, other)
	} catch (_: ArithmeticException) {
		invalidResponse()
	}

	private fun Long.checkedNegate(): Long = try {
		Math.negateExact(this)
	} catch (_: ArithmeticException) {
		invalidResponse()
	}

	private fun JsonNode.textOrNull(): String? = takeIf { it.isTextual }?.stringValue()

	private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

	private companion object {
		const val AI_USAGE_EVENT = "plot_ai_usage"
		const val EVENT_PAGE_SIZE = 100
	}
}

private data class PolarMeterState(
	val balance: Long,
	val creditedUnits: Long,
	val consumedUnits: Long,
)
