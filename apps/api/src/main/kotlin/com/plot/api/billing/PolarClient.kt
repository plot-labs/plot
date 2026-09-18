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

data class PolarCustomer(val id: String, val externalId: String)

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
	fun ensureCustomer(workspaceId: UUID, ownerEmail: String, workspaceName: String): PolarCustomer
	fun readCreditBalance(workspaceId: UUID): Long
	fun grantTrialCredits(workspaceId: UUID): PolarEventResult
	fun ingestCredits(workspaceId: UUID, eventId: String, credits: Long, metadata: Map<String, Any> = emptyMap()): PolarEventResult
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
) : PolarCreditProvider {
	fun workspaceExternalId(workspaceId: UUID): String = "plot-workspace:$workspaceId"

	override fun ensureCustomer(workspaceId: UUID, ownerEmail: String, workspaceName: String): PolarCustomer {
		ensureEnabled()
		val externalId = workspaceExternalId(workspaceId)
		findCustomer(externalId)?.let { return it }
		val payload = mapOf(
			"external_id" to externalId,
			"email" to ownerEmail,
			"name" to workspaceName,
			"type" to "team",
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
		ensureEnabled()
		val externalId = workspaceExternalId(workspaceId)
		val response = execute("GET", "/v1/customers/external/${segment(externalId)}/state")
		val root = parse(response.body)
		val meters = root.path("active_meters")
		if (!meters.isArray) invalidResponse()
		val meter = meters.firstOrNull { it.path("meter_id").stringValue() == properties.aiMeterId }
			?: throw PolarApiException("POLAR_AI_METER_MISSING", "Polar AI credit meter is unavailable")
		return try {
			meter.path("balance").decimalValue().longValueExact()
		} catch (_: RuntimeException) {
			invalidResponse()
		}
	}

	override fun grantTrialCredits(workspaceId: UUID): PolarEventResult = ingest(
		workspaceId = workspaceId,
		eventId = "trial:$workspaceId",
		credits = -properties.trialCredits,
		metadata = mapOf(
			"reason" to "trial_grant",
			"policy_version" to properties.trialPolicyVersion,
		),
	)

	override fun ingestCredits(
		workspaceId: UUID,
		eventId: String,
		credits: Long,
		metadata: Map<String, Any>,
	): PolarEventResult {
		require(credits > 0) { "Usage credits must be positive" }
		return ingest(workspaceId, eventId, credits, metadata)
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
					"id" to eventId,
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

	private fun findCustomer(externalId: String): PolarCustomer? {
		val response = execute(
			"GET",
			"/v1/customers/external/${segment(externalId)}",
			accepted = setOf(200, 404),
		)
		if (response.status == 404) return null
		return parseCustomer(response.body, externalId)
	}

	private fun parseCustomer(body: String, expectedExternalId: String): PolarCustomer {
		val root = parse(body)
		val id = root.path("id").stringValue()?.takeIf(String::isNotBlank) ?: invalidResponse()
		val externalId = root.path("external_id").stringValue()?.takeIf(String::isNotBlank) ?: invalidResponse()
		if (externalId != expectedExternalId) {
			throw PolarApiException("POLAR_CUSTOMER_OWNERSHIP_MISMATCH", "Polar customer does not belong to this workspace")
		}
		return PolarCustomer(id, externalId)
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

	private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

	private companion object {
		const val AI_USAGE_EVENT = "plot_ai_usage"
	}
}
