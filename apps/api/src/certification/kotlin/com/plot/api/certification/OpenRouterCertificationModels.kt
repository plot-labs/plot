package com.plot.api.certification

import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat

enum class CertificationDisposition { BLOCKED, INCONCLUSIVE }

enum class CertificationFailureCode {
	NON_CANONICAL_ORIGIN,
	REDIRECT_REJECTED,
	MISSING_CREDENTIAL,
	AUTHENTICATION_REJECTED,
	KEY_POLICY_INVALID,
	QUOTA_EXHAUSTED,
	RATE_LIMITED,
	PROVIDER_UNAVAILABLE,
	INVENTORY_INVALID,
	MODEL_ID_MISMATCH,
	PROVIDER_NOT_AVAILABLE,
	AMBIGUOUS_ENDPOINT,
	IMMUTABLE_MODEL_REQUIRED,
	MODEL_EXPIRED,
	STRUCTURED_OUTPUT_UNSUPPORTED,
	NO_ZDR_ENDPOINT,
	CANARY_CONTRACT_INVALID,
	METADATA_PENDING,
	METADATA_INVALID,
	ATTRIBUTION_MISMATCH,
}

class CertificationPreflightException(
	val disposition: CertificationDisposition,
	val code: CertificationFailureCode,
	cause: Throwable? = null,
) : RuntimeException("Certification preflight failed: ${code.name}", cause)

data class OpenRouterPreflightRequest(
	val requestedModel: String,
	val providerSlug: String,
	val requireZeroDataRetention: Boolean = true,
)

data class VerifiedOpenRouterEndpoint(
	val requestedModel: String,
	val providerSlug: String,
)

data class NativeTokenUsage(
	val prompt: Int,
	val completion: Int,
	val reasoning: Int,
	val cached: Int,
)

/**
 * A deliberately allow-listed projection of OpenRouter generation metadata.
 * Raw request IDs, prompts, completions, source labels, and provider bodies never belong here.
 */
data class OpenRouterGenerationAttribution(
	val servedModel: String,
	val providerSlug: String,
	val nativeTokens: NativeTokenUsage,
	val costUsdMicros: Long,
	val latencyMs: Int,
	val generationTimeMs: Int,
	val finishReason: String,
	val byok: Boolean,
	val responseIdHash: String,
	val upstreamIdHash: String,
)

class GenerationMetadataRequest(internal val responseId: String) {
	init {
		if (!RESPONSE_ID.matches(responseId)) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.METADATA_INVALID)
		}
	}

	override fun toString(): String = "GenerationMetadataRequest(responseId=<redacted>)"

	companion object {
		private val RESPONSE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{2,255}$")
	}
}

data class MetadataPollingPolicy(
	val maxAttempts: Int = 80,
	val consistencyWindow: Duration = Duration.ofSeconds(20),
	val delay: Duration = Duration.ofMillis(250),
) {
	init {
		require(maxAttempts in 1..100) { "metadata max attempts must be from 1 to 100" }
		require(!consistencyWindow.isNegative && !consistencyWindow.isZero) { "metadata consistency window must be positive" }
		require(!delay.isNegative) { "metadata polling delay must not be negative" }
	}
}

data class ProductionCanaryResult(
	val metadata: ProductionCanaryMetadata,
	val structuredOutputParsed: Boolean,
)

/** Safe projection copied from ModelCallMetadata after a production-transport canary call. */
class ProductionCanaryMetadata(
	val responseId: String?,
	val servedModel: String?,
	val gateway: String?,
	val requestedModel: String?,
) {
	override fun toString(): String =
		"ProductionCanaryMetadata(responseId=<redacted>, servedModel=$servedModel, gateway=$gateway, requestedModel=$requestedModel)"
}

fun interface ProductionStructuredCanary {
	/** Executes exactly one production Spring AI transport call with non-sensitive synthetic input. */
	fun execute(): ProductionCanaryResult
}

data class VerifiedOpenRouterAttribution(
	val requestedModel: String,
	val servedModel: String,
	val providerSlug: String,
	val nativeTokens: NativeTokenUsage,
	val costUsdMicros: Long,
	val latencyMs: Int,
	val generationTimeMs: Int,
	val finishReason: String,
	val byok: Boolean,
	val responseIdHash: String,
	val upstreamIdHash: String,
)

data class CompletedOpenRouterPreflight<T>(
	val attribution: VerifiedOpenRouterAttribution,
	val sealedModelExecution: T,
)

enum class SensitiveSourceCode {
	PRIVATE_KEY,
	ACCESS_TOKEN,
	EMAIL_ADDRESS,
	CUSTOMER_IDENTIFIER,
	CREDENTIAL_ASSIGNMENT,
	CONNECTION_STRING,
	WEBHOOK_URL,
	PHONE_NUMBER,
	GOVERNMENT_IDENTIFIER,
	PROMPT_INJECTION,
}

class ApprovedSourceSnapshot(val sourceAlias: String, fields: List<String>) {
	private val values = fields.toList()

	init {
		if (!SOURCE_ALIAS.matches(sourceAlias)) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.INVENTORY_INVALID)
		}
	}

	internal fun contents(): Sequence<String> = values.asSequence()

	override fun toString(): String = "ApprovedSourceSnapshot(sourceAlias=$sourceAlias, fields=<redacted>)"

	companion object {
		private val SOURCE_ALIAS = Regex("^source-[a-f0-9]{16,64}$")
	}
}

data class SensitiveSourceScanResult(
	val sourceAlias: String,
	val eligible: Boolean,
	val codes: Set<SensitiveSourceCode>,
)

class SensitiveSourcePreflight {
	fun scan(snapshot: ApprovedSourceSnapshot): SensitiveSourceScanResult {
		val codes = linkedSetOf<SensitiveSourceCode>()
		for (content in snapshot.contents()) {
			for ((code, pattern) in PATTERNS) {
				if (code !in codes && pattern.containsMatchIn(content)) codes += code
			}
			if (codes.size == PATTERNS.size) break
		}
		return SensitiveSourceScanResult(snapshot.sourceAlias, codes.isEmpty(), codes.toSortedSet())
	}

	companion object {
		private val PATTERNS = mapOf(
			SensitiveSourceCode.PRIVATE_KEY to Regex("-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----", RegexOption.IGNORE_CASE),
			SensitiveSourceCode.ACCESS_TOKEN to Regex(
				"(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|sk-[A-Za-z0-9_-]{20,}|AKIA[A-Z0-9]{16}|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})",
			),
			SensitiveSourceCode.EMAIL_ADDRESS to Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])"),
			SensitiveSourceCode.CUSTOMER_IDENTIFIER to Regex("(?i)\\b(?:customer|account)[_ -]?(?:id|identifier)\\s*[:=]\\s*[A-Za-z0-9_-]{6,}"),
			SensitiveSourceCode.CREDENTIAL_ASSIGNMENT to Regex(
				"(?i)\\b(?:password|passwd|api[_-]?key|client[_-]?secret|access[_-]?token)\\s*[:=]\\s*[\"']?[^\\s\"']{8,}",
			),
			SensitiveSourceCode.CONNECTION_STRING to Regex(
				"(?i)\\b(?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis|amqps?)://[^\\s]+|jdbc:[a-z0-9]+://[^\\s]+",
			),
			SensitiveSourceCode.WEBHOOK_URL to Regex("https://hooks\\.slack\\.com/services/[A-Za-z0-9/_-]{20,}"),
			SensitiveSourceCode.PHONE_NUMBER to Regex("(?<![A-Za-z0-9_-])\\+?[1-9][0-9]{0,2}[- .]?(?:\\([0-9]{2,4}\\)|[0-9]{2,4})[- .]?[0-9]{3,4}[- .]?[0-9]{4}(?![A-Za-z0-9_-])"),
			SensitiveSourceCode.GOVERNMENT_IDENTIFIER to Regex("(?<![0-9])(?:[0-9]{3}-[0-9]{2}-[0-9]{4}|[0-9]{6}-[1-4][0-9]{6})(?![0-9])"),
			SensitiveSourceCode.PROMPT_INJECTION to Regex(
				"(?i)(?:ignore|disregard|override)\\s+(?:all\\s+)?(?:previous|prior|system)\\s+instructions|reveal\\s+(?:the\\s+)?(?:hidden|system|private)\\s+(?:prompt|evidence|instructions)|system\\s+prompt",
			),
		)
	}
}

class CanonicalExternalOriginPolicy {
	fun requireOpenRouterApi(value: String): URI = requireExact(value, OPENROUTER_API)

	fun requireGitHubApi(value: String): URI = requireExact(value, GITHUB_API)

	fun rejectRedirect(@Suppress("UNUSED_PARAMETER") location: String?): Nothing =
		throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.REDIRECT_REJECTED)

	private fun requireExact(value: String, expected: String): URI {
		val uri = try {
			URI(value)
		} catch (_: RuntimeException) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.NON_CANONICAL_ORIGIN)
		}
		if (value != expected || uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.NON_CANONICAL_ORIGIN)
		}
		return uri
	}

	companion object {
		const val OPENROUTER_API = "https://openrouter.ai/api/v1"
		const val GITHUB_API = "https://api.github.com"
	}
}

object CertificationEnvironmentPolicy {
	private val COMMON = setOf("PATH", "JAVA_HOME", "HOME", "TMPDIR", "LANG", "LC_ALL", "TZ")

	fun openRouterPreflight(source: Map<String, String>): Map<String, String> = scoped(source, "OPENROUTER_API_KEY")

	fun githubPreflight(source: Map<String, String>): Map<String, String> = scoped(source, "GITHUB_TOKEN")

	private fun scoped(source: Map<String, String>, credential: String): Map<String, String> {
		if (source[credential].isNullOrBlank()) {
			throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.MISSING_CREDENTIAL)
		}
		return RedactedEnvironment(source.filterKeys { it in COMMON || it == credential })
	}

	private class RedactedEnvironment(private val delegate: Map<String, String>) : Map<String, String> by delegate {
		override fun toString(): String = "ScopedEnvironment(keys=${keys.sorted()}, values=<redacted>)"
	}
}

internal fun validModelIdentity(model: String): Boolean =
	Regex("^[a-z0-9][a-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._:-]*$").matches(model)

internal fun parseExpiration(value: String): Instant = try {
	Instant.parse(value)
} catch (_: DateTimeParseException) {
	throw CertificationPreflightException(CertificationDisposition.BLOCKED, CertificationFailureCode.INVENTORY_INVALID)
}

internal fun safeSha256(namespace: String, value: String): String = "sha256:" + HexFormat.of().formatHex(
	MessageDigest.getInstance("SHA-256").digest("$namespace:$value".toByteArray(Charsets.UTF_8)),
)
