package com.plot.api.certification

import java.io.IOException
import java.math.RoundingMode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

private val PROVIDER_SLUG = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")

private fun JsonNode.text(name: String): String? = get(name)?.takeIf(JsonNode::isString)?.stringValue()

class CertificationHttpResponse(val status: Int, val body: String) {
	override fun toString(): String = "CertificationHttpResponse(status=$status, body=<redacted>)"
}

fun interface OpenRouterCertificationTransport {
	fun get(relativePathAndQuery: String): CertificationHttpResponse
}

class JdkOpenRouterCertificationTransport private constructor(
	private val baseUri: URI,
	private val apiKey: String,
	private val httpClient: HttpClient,
) : OpenRouterCertificationTransport {
	override fun get(relativePathAndQuery: String): CertificationHttpResponse {
		if (!relativePathAndQuery.startsWith("/") || relativePathAndQuery.startsWith("//")) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.NON_CANONICAL_ORIGIN)
		}
		val uri = try {
			URI(baseUri.toString() + relativePathAndQuery)
		} catch (_: RuntimeException) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.NON_CANONICAL_ORIGIN)
		}
		if (uri.scheme != baseUri.scheme || uri.host != baseUri.host || uri.port != baseUri.port || uri.userInfo != null) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.NON_CANONICAL_ORIGIN)
		}
		val request = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(15))
			.header("Accept", "application/json")
			.header("Authorization", "Bearer $apiKey")
			.GET()
			.build()
		val response = try {
			httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
			throw CertificationPreflightException(CertificationDisposition.INCONCLUSIVE, CertificationFailureCode.PROVIDER_UNAVAILABLE)
		} catch (_: IOException) {
			throw CertificationPreflightException(CertificationDisposition.INCONCLUSIVE, CertificationFailureCode.PROVIDER_UNAVAILABLE)
		}
		if (response.statusCode() in 300..399) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.REDIRECT_REJECTED)
		}
		if (response.uri().scheme != baseUri.scheme || response.uri().host != baseUri.host || response.uri().port != baseUri.port) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.NON_CANONICAL_ORIGIN)
		}
		return CertificationHttpResponse(response.statusCode(), response.body())
	}

	companion object {
		fun live(apiKey: String, policy: CanonicalExternalOriginPolicy = CanonicalExternalOriginPolicy()): JdkOpenRouterCertificationTransport {
			if (apiKey.isBlank()) {
				throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.MISSING_CREDENTIAL)
			}
			return JdkOpenRouterCertificationTransport(
				policy.requireOpenRouterApi(CanonicalExternalOriginPolicy.OPENROUTER_API),
				apiKey,
				client(),
			)
		}

		fun loopbackForTests(baseUrl: String, apiKey: String): JdkOpenRouterCertificationTransport {
			val uri = URI(baseUrl)
			require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0 && uri.path == "/api/v1")
			require(uri.userInfo == null && uri.query == null && uri.fragment == null)
			return JdkOpenRouterCertificationTransport(uri, apiKey, client())
		}

		private fun client(): HttpClient = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.build()
	}
}

class OpenRouterCertificationClient(
	private val transport: OpenRouterCertificationTransport,
	private val mapper: ObjectMapper = ObjectMapper(),
	private val now: () -> Instant = Instant::now,
) {
	fun selectEndpoint(request: OpenRouterPreflightRequest): VerifiedOpenRouterEndpoint {
		if (!MODEL_ID.matches(request.requestedModel) || !PROVIDER_SLUG.matches(request.providerSlug)) {
			fail(CertificationFailureCode.INVENTORY_INVALID)
		}
		validateCurrentKey()
		val response = transport.get("/models/${request.requestedModel}/endpoints")
		classify(response.status, pendingIsAllowed = false)
		val data = parse(response.body, CertificationFailureCode.INVENTORY_INVALID).get("data")
			?.takeIf(JsonNode::isObject) ?: fail(CertificationFailureCode.INVENTORY_INVALID)
		if (data.text("id") != request.requestedModel) fail(CertificationFailureCode.MODEL_ID_MISMATCH)
		val endpoints = data.get("endpoints")?.takeIf(JsonNode::isArray)?.asArray()?.values()?.toList()
			?: fail(CertificationFailureCode.INVENTORY_INVALID)
		val providerEndpoints = endpoints.filter { it.isObject && it.text("tag") == request.providerSlug }
		if (providerEndpoints.isEmpty()) fail(CertificationFailureCode.PROVIDER_NOT_AVAILABLE)
		if (providerEndpoints.size != 1) fail(CertificationFailureCode.AMBIGUOUS_ENDPOINT)
		val endpoint = providerEndpoints.single()
		if (endpoint.has("expiration_date") && !endpoint.get("expiration_date").isNull &&
			!parseExpiration(endpoint.get("expiration_date").stringValue()).isAfter(now())
		) {
			fail(CertificationFailureCode.MODEL_EXPIRED)
		}
		val supportedParameters = endpoint.get("supported_parameters")?.takeIf(JsonNode::isArray)
			?.asArray()?.values()?.mapNotNull { it.takeIf(JsonNode::isString)?.stringValue() }?.toSet().orEmpty()
		if ("response_format" !in supportedParameters) {
			fail(CertificationFailureCode.STRUCTURED_OUTPUT_UNSUPPORTED)
		}

		if (request.requireZeroDataRetention) validateZdrEndpoint(request)
		return VerifiedOpenRouterEndpoint(request.requestedModel, request.providerSlug)
	}

	private fun validateZdrEndpoint(request: OpenRouterPreflightRequest) {
		val response = transport.get("/endpoints/zdr")
		classify(response.status, pendingIsAllowed = false)
		val endpoints = parse(response.body, CertificationFailureCode.INVENTORY_INVALID).get("data")
			?.takeIf(JsonNode::isArray)?.asArray()?.values()?.toList()
			?: fail(CertificationFailureCode.INVENTORY_INVALID)
		val eligible = endpoints.filter {
			it.isObject && it.text("model_id") == request.requestedModel && it.text("tag") == request.providerSlug &&
				it.get("supported_parameters")?.takeIf(JsonNode::isArray)?.asArray()?.values()
					?.any { parameter -> parameter.isString && parameter.stringValue() == "response_format" } == true
		}
		if (eligible.isEmpty()) fail(CertificationFailureCode.NO_ZDR_ENDPOINT)
		if (eligible.size != 1) fail(CertificationFailureCode.AMBIGUOUS_ENDPOINT)
	}

	private fun validateCurrentKey() {
		val response = transport.get("/key")
		classify(response.status, pendingIsAllowed = false)
		val data = parse(response.body, CertificationFailureCode.KEY_POLICY_INVALID).get("data")
			?.takeIf(JsonNode::isObject) ?: fail(CertificationFailureCode.KEY_POLICY_INVALID)
		val remaining = data.get("limit_remaining")
		if (remaining != null && !remaining.isNull) {
			if (!remaining.isNumber || !remaining.doubleValue().isFinite()) {
				fail(CertificationFailureCode.KEY_POLICY_INVALID)
			}
			if (remaining.doubleValue() <= 0.0) fail(CertificationFailureCode.QUOTA_EXHAUSTED)
		}
	}

	private fun parse(body: String, code: CertificationFailureCode): JsonNode = try {
		mapper.readTree(body)
	} catch (_: RuntimeException) {
		fail(code)
	}

	private fun fail(code: CertificationFailureCode): Nothing =
		throw CertificationPreflightException(CertificationDisposition.BLOCKED, code)

	companion object {
		private val MODEL_ID = Regex("^[a-z0-9][a-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._:-]*$")
	}
}

class OpenRouterGenerationMetadataClient(
	private val transport: OpenRouterCertificationTransport,
	private val polling: MetadataPollingPolicy = MetadataPollingPolicy(),
	private val mapper: ObjectMapper = ObjectMapper(),
	private val now: () -> Instant = Instant::now,
	private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
	fun fetch(request: GenerationMetadataRequest): OpenRouterGenerationAttribution {
		val deadline = now().plus(polling.consistencyWindow)
		val encodedId = URLEncoder.encode(request.responseId, StandardCharsets.UTF_8)
		var attempt = 0
		while (attempt < polling.maxAttempts && (attempt == 0 || !now().isAfter(deadline))) {
			attempt += 1
			val response = transport.get("/generation?id=$encodedId")
			if (response.status != 404) {
				classify(response.status, pendingIsAllowed = true)
				val projection = parseProjection(response.body, request.responseId)
				if (projection != null) return projection
			}
			if (attempt < polling.maxAttempts) {
				val remaining = Duration.between(now(), deadline)
				if (remaining.isNegative || remaining.isZero) break
				sleep(minOf(polling.delay, remaining))
			}
		}
		throw CertificationPreflightException(CertificationDisposition.INCONCLUSIVE, CertificationFailureCode.METADATA_PENDING)
	}

	private fun parseProjection(body: String, expectedResponseId: String): OpenRouterGenerationAttribution? {
		val root = try {
			mapper.readTree(body)
		} catch (_: RuntimeException) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.METADATA_INVALID)
		}
		val data = root.get("data")?.takeUnless(JsonNode::isNull)?.takeIf(JsonNode::isObject) ?: return null
		val responseId = data.text("id") ?: return null
		if (responseId != expectedResponseId) attributionMismatch()
		val servedModel = data.text("model") ?: return null
		val provider = data.text("provider_name")?.let(::canonicalProviderSlug) ?: return null
		if (!validModelIdentity(servedModel) || !PROVIDER_SLUG.matches(provider)) attributionMismatch()
		val prompt = data.nonNegativeInt("native_tokens_prompt") ?: return null
		val completion = data.nonNegativeInt("native_tokens_completion") ?: return null
		val reasoning = data.nonNegativeInt("native_tokens_reasoning") ?: return null
		val cached = data.nonNegativeInt("native_tokens_cached") ?: return null
		val cost = data.get("total_cost")?.takeIf(JsonNode::isNumber)?.decimalValue() ?: return null
		if (cost.signum() < 0) metadataInvalid()
		val costMicros = try {
			cost.multiply(java.math.BigDecimal(1_000_000)).setScale(0, RoundingMode.HALF_UP).longValueExact()
		} catch (_: ArithmeticException) {
			metadataInvalid()
		}
		val latency = data.nonNegativeInt("latency") ?: return null
		val generationTime = data.nonNegativeInt("generation_time") ?: return null
		val finishReason = data.text("finish_reason")?.takeIf(FINISH_REASON::matches) ?: return null
		val byok = data.get("is_byok")?.takeIf(JsonNode::isBoolean)?.booleanValue() ?: return null
		val upstreamId = data.text("upstream_id") ?: return null
		val providerResponses = data.get("provider_responses")
		if (providerResponses != null && !providerResponses.isNull &&
			(!providerResponses.isArray || providerResponses.asArray().size() != 1)
		) attributionMismatch()
		return OpenRouterGenerationAttribution(
			servedModel = servedModel,
			providerSlug = provider,
			nativeTokens = NativeTokenUsage(prompt, completion, reasoning, cached),
			costUsdMicros = costMicros,
			latencyMs = latency,
			generationTimeMs = generationTime,
			finishReason = finishReason,
			byok = byok,
			responseIdHash = safeSha256("openrouter-response", responseId),
			upstreamIdHash = safeSha256("openrouter-upstream", upstreamId),
		)
	}

	private fun JsonNode.nonNegativeInt(name: String): Int? {
		val value = get(name) ?: return null
		if (!value.isIntegralNumber || !value.canConvertToInt()) metadataInvalid()
		return value.intValue().also { if (it < 0) metadataInvalid() }
	}

	private fun attributionMismatch(): Nothing =
		throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.ATTRIBUTION_MISMATCH)

	private fun metadataInvalid(): Nothing =
		throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.METADATA_INVALID)

	private fun canonicalProviderSlug(providerName: String): String = providerName.trim().lowercase()
		.replace(Regex("[^a-z0-9]+"), "-")
		.trim('-')
		.takeIf(PROVIDER_SLUG::matches) ?: attributionMismatch()

	companion object {
		private val FINISH_REASON = Regex("^[a-z][a-z0-9_-]{1,63}$")
	}
}

class OpenRouterPreflight(
	private val certificationClient: OpenRouterCertificationClient,
	private val metadataClient: OpenRouterGenerationMetadataClient,
) {
	fun <T> run(
		request: OpenRouterPreflightRequest,
		canary: ProductionStructuredCanary,
		sealModelExecution: (VerifiedOpenRouterAttribution) -> T,
	): CompletedOpenRouterPreflight<T> {
		val endpoint = certificationClient.selectEndpoint(request)
		val canaryResult = canary.execute()
		val canaryMetadata = canaryResult.metadata
		if (!canaryResult.structuredOutputParsed || canaryMetadata.gateway != "openrouter" ||
			canaryMetadata.requestedModel != request.requestedModel || canaryMetadata.responseId.isNullOrBlank() ||
			canaryMetadata.servedModel == null
		) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.CANARY_CONTRACT_INVALID)
		}
		if (!validModelIdentity(canaryMetadata.servedModel)) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.CANARY_CONTRACT_INVALID)
		}
		val metadata = metadataClient.fetch(GenerationMetadataRequest(canaryMetadata.responseId))
		val canaryModelMatches = canaryMetadata.servedModel == metadata.servedModel ||
			canaryMetadata.servedModel == request.requestedModel
		if (!canaryModelMatches || metadata.providerSlug != endpoint.providerSlug) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.ATTRIBUTION_MISMATCH)
		}
		val verified = VerifiedOpenRouterAttribution(
			requestedModel = request.requestedModel,
			servedModel = metadata.servedModel,
			providerSlug = metadata.providerSlug,
			nativeTokens = metadata.nativeTokens,
			costUsdMicros = metadata.costUsdMicros,
			latencyMs = metadata.latencyMs,
			generationTimeMs = metadata.generationTimeMs,
			finishReason = metadata.finishReason,
			byok = metadata.byok,
			responseIdHash = metadata.responseIdHash,
			upstreamIdHash = metadata.upstreamIdHash,
		)
		return CompletedOpenRouterPreflight(verified, sealModelExecution(verified))
	}
}

private fun classify(status: Int, pendingIsAllowed: Boolean) {
	when (status) {
		in 200..299 -> return
		401, 403 -> throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.AUTHENTICATION_REJECTED)
		402 -> throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.QUOTA_EXHAUSTED)
		404 -> throw CertificationPreflightException(
			if (pendingIsAllowed) CertificationDisposition.INCONCLUSIVE else CertificationDisposition.BLOCKED,
			if (pendingIsAllowed) CertificationFailureCode.METADATA_PENDING else CertificationFailureCode.PROVIDER_NOT_AVAILABLE,
		)
		429 -> throw CertificationPreflightException(CertificationDisposition.INCONCLUSIVE, CertificationFailureCode.RATE_LIMITED)
		in 500..599 -> throw CertificationPreflightException(CertificationDisposition.INCONCLUSIVE, CertificationFailureCode.PROVIDER_UNAVAILABLE)
		else -> throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.INVENTORY_INVALID)
	}
}
