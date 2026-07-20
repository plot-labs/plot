package com.plot.api.certification

import com.plot.api.ai.provider.ModelSchemas
import com.plot.api.config.PlotAiProperties
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import tools.jackson.module.kotlin.jacksonObjectMapper

data class CertificationProfileHashes(
	val schemaVersion: String = "certification-generation-profile-v1",
	val matrixProfileHash: String,
	val nanoModelProfileHash: String,
	val miniModelProfileHash: String,
)

data class CertificationProfileSchemas(
	val writer: String = ModelSchemas.WRITER,
	val reviewer: String = ModelSchemas.REVIEWER,
	val rewriter: String = ModelSchemas.REWRITE,
)

/** Canonical contract for every option that changes the OpenAI-compatible request profile. */
object CertificationGenerationProfileContract {
	const val PROMPT_CONTRACT_VERSION = "changelog-grounding-v4"

	fun properties(
		model: String,
		providerSlug: String,
		timeoutSeconds: Long,
		maxOutputTokens: Int,
		transportRetries: Int,
		schemaRetries: Int,
	): PlotAiProperties = PlotAiProperties(
		enabled = true,
		model = model,
		routingProvider = providerSlug,
		timeout = Duration.ofSeconds(timeoutSeconds),
		transportRetries = transportRetries,
		schemaRetries = schemaRetries,
		maxOutputTokens = maxOutputTokens,
	)

	fun hashes(propertiesByModel: Map<String, PlotAiProperties>): CertificationProfileHashes {
		if (propertiesByModel.keys != REQUIRED_MODELS || propertiesByModel.any { (model, properties) -> properties.model != model }) reject()
		val nano = modelProfileHash(propertiesByModel.getValue(PlotAiProperties.GPT_5_4_NANO_MODEL))
		val mini = modelProfileHash(propertiesByModel.getValue(PlotAiProperties.GPT_4O_MINI_MODEL))
		return CertificationProfileHashes(
			matrixProfileHash = digest("matrix-profile-v2", listOf(
				"nano=$nano",
				"mini=$mini",
				"selection=nano-first-mini-fallback",
			).joinToString("\n")),
			nanoModelProfileHash = nano,
			miniModelProfileHash = mini,
		)
	}

	fun modelProfileHash(
		properties: PlotAiProperties,
		schemas: CertificationProfileSchemas = CertificationProfileSchemas(),
	): String {
		val model = requireNotNull(properties.model)
		if (!properties.enabled || !properties.configured || model !in REQUIRED_MODELS) reject()
		val temperatureMode = if (properties.supportsTemperature) {
			"writer=${decimal(properties.writerTemperature)},reviewer=${decimal(properties.reviewerTemperature)}"
		} else "omitted"
		return digest("model-profile-v2", listOf(
			"gateway=${PlotAiProperties.OPENROUTER_GATEWAY}",
			"baseUrl=${PlotAiProperties.OPENROUTER_BASE_URL}",
			"model=$model",
			"timeoutMs=${properties.timeout.toMillis()}",
			"maxCompletionTokens=${properties.maxOutputTokens}",
			"transportRetries=${properties.transportRetries}",
			"schemaRetries=${properties.schemaRetries}",
			"frameworkRetries=0",
			"temperature=$temperatureMode",
			"writerSchema=${digest("json-schema", schemas.writer)}",
			"reviewerSchema=${digest("json-schema", schemas.reviewer)}",
			"rewriterSchema=${digest("json-schema", schemas.rewriter)}",
			"toolCallbacks=none",
			"metadataHeader=enabled",
			"provider=${requireNotNull(properties.routingProvider)}",
			"allowFallbacks=${properties.allowFallbacks}",
			"requireParameters=${properties.requireParameters}",
			"dataCollection=deny",
			"zdr=${properties.zeroDataRetention}",
			"contentLogging=${properties.contentLoggingEnabled}",
				"promptContract=$PROMPT_CONTRACT_VERSION",
		).joinToString("\n"))
	}

	private fun decimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
	private fun digest(namespace: String, value: String): String = "sha256:" + HexFormat.of().formatHex(
		MessageDigest.getInstance("SHA-256").digest(
			namespace.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + value.toByteArray(Charsets.UTF_8),
		),
	)
	private fun reject(): Nothing = throw CertificationProfileException()
	private val REQUIRED_MODELS = setOf(PlotAiProperties.GPT_5_4_NANO_MODEL, PlotAiProperties.GPT_4O_MINI_MODEL)
}

object CertificationGenerationProfileCli {
	@JvmStatic
	fun main(args: Array<String>) {
		if (args.isNotEmpty() || System.getenv().keys.any(PROVIDER_CREDENTIALS::contains)) throw CertificationProfileException()
		val provider = required("PLOT_AI_ROUTING_PROVIDER")
		val timeout = number("PLOT_AI_TIMEOUT_SECONDS", 45).toLong()
		val maxTokens = number("PLOT_AI_MAX_OUTPUT_TOKENS", 2_000)
		val transportRetries = number("PLOT_AI_TRANSPORT_RETRIES", 0)
		val schemaRetries = number("PLOT_AI_SCHEMA_RETRIES", 1)
		val properties = listOf(PlotAiProperties.GPT_5_4_NANO_MODEL, PlotAiProperties.GPT_4O_MINI_MODEL).associateWith { model ->
			CertificationGenerationProfileContract.properties(
				model, provider, timeout, maxTokens, transportRetries, schemaRetries,
			)
		}
		val bytes = jacksonObjectMapper().writeValueAsBytes(CertificationGenerationProfileContract.hashes(properties))
		val target = Path.of(required("PLOT_CERTIFICATION_PROFILE_OUTPUT"))
		if (!target.isAbsolute || bytes.isEmpty() || bytes.size > 16_384) throw CertificationProfileException()
		Files.createDirectories(target.parent)
		if (Files.isSymbolicLink(target.parent)) throw CertificationProfileException()
		try {
			Files.newOutputStream(target.normalize(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(bytes) }
		} catch (_: Exception) {
			throw CertificationProfileException()
		}
	}

	private fun required(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank) ?: throw CertificationProfileException()
	private fun number(name: String, default: Int): Int = System.getenv(name)?.takeIf(String::isNotBlank)?.toIntOrNull() ?: default
	private val PROVIDER_CREDENTIALS = setOf(
		"OPENROUTER_API_KEY", "OPENAI_API_KEY", "SPRING_AI_OPENAI_API_KEY", "GITHUB_TOKEN", "GH_TOKEN",
		"GITHUB_APP_PRIVATE_KEY", "GITHUB_INSTALLATION_TOKEN", "PLOT_GITHUB_PRIVATE_KEY", "PLOT_GITHUB_STATE_SECRET",
	)
}

class CertificationProfileException : IllegalStateException("CERTIFICATION_GENERATION_PROFILE_REJECTED")
