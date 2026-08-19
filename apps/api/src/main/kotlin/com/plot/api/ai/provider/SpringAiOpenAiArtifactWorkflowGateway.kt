package com.plot.api.ai.provider

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.openai.errors.OpenAIRetryableException
import com.openai.errors.OpenAIServiceException
import com.plot.api.ai.prompt.ChangelogPrompt
import com.plot.api.ai.prompt.ChangelogPromptFactory
import com.plot.api.config.PlotAiProperties
import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import com.plot.api.artifact.workflow.model.WriterOutput
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

internal fun <T : Any> ObjectMapper.readJsonObject(content: String?, responseType: Class<T>): T {
	val text = content?.trim().orEmpty()
	val starts = text.indices.filter { text[it] == '{' }.asReversed()
	val ends = text.indices.filter { text[it] == '}' }.asReversed()
	var attempts = 0
	var lastFailure: RuntimeException? = null
	val firstStart = text.indexOf('{')
	val lastEnd = text.lastIndexOf('}')
	if (firstStart >= 0 && lastEnd >= firstStart) {
		attempts++
		try {
			return readPromptedValue(text.substring(firstStart, lastEnd + 1), responseType)
		} catch (failure: RuntimeException) {
			lastFailure = failure
		}
	}
	candidateLoop@ for (start in starts) {
		for (end in ends) {
			if (end < start) break
			if (attempts++ >= MAX_JSON_PARSE_ATTEMPTS) break@candidateLoop
			try {
				return readPromptedValue(text.substring(start, end + 1), responseType)
			} catch (failure: RuntimeException) {
				lastFailure = failure
			}
		}
	}
	for (start in starts) {
		if (attempts++ >= MAX_JSON_PARSE_ATTEMPTS) break
		val repaired = repairIncompleteJson(text.substring(start).substringBefore("```").trim()) ?: continue
		try {
			return readPromptedValue(repaired, responseType)
		} catch (failure: RuntimeException) {
			lastFailure = failure
		}
	}
	throw MalformedModelOutputException("Structured response body is missing or invalid", lastFailure)
}

private fun <T : Any> ObjectMapper.readPromptedValue(value: String, responseType: Class<T>): T {
	val root = readTree(value)
	normalizeNullableCollections(root)
	schemaFor(responseType)?.let { schema ->
		if (PROMPTED_SCHEMA_REGISTRY.getSchema(schema, InputFormat.JSON).validate(root).isNotEmpty()) {
			throw MalformedModelOutputException("Structured response failed local schema validation")
		}
	}
	return treeToValue(root, responseType)
}

private fun schemaFor(responseType: Class<*>): String? = when (responseType) {
	AgentDecision::class.java -> SpringAiAgentDecisionGateway.decisionSchema
	WriterOutput::class.java -> ModelSchemas.WRITER
	ReviewerOutput::class.java -> ModelSchemas.REVIEWER
	TargetedRewriteOutput::class.java -> ModelSchemas.REWRITE
	else -> null
}

private const val MAX_JSON_PARSE_ATTEMPTS = 64
private val PROMPTED_SCHEMA_REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

internal fun isTransientOpenRouterStatus(status: Int): Boolean =
	status == 408 || status == 409 || status == 429 || status >= 500

private fun normalizeNullableCollections(node: JsonNode) {
	when {
		node.isObject -> {
			val objectNode = node as ObjectNode
			objectNode.properties().toList().forEach { property ->
				val normalizedName = property.key.trim()
				if (normalizedName != property.key && !objectNode.has(normalizedName)) {
					objectNode.set(normalizedName, property.value)
					objectNode.remove(property.key)
				}
			}
			NULLABLE_COLLECTION_FIELDS.forEach { field ->
				if (objectNode.path(field).isNull) objectNode.putArray(field)
			}
			if (objectNode.path("intent").isNull) objectNode.put("intent", "FACTUAL")
			if (objectNode.path("omit").isNull) objectNode.put("omit", false)
			objectNode.properties().forEach { normalizeNullableCollections(it.value) }
		}
		node.isArray -> node.forEach(::normalizeNullableCollections)
	}
}

private val NULLABLE_COLLECTION_FIELDS = setOf(
	"conflictEvidenceIds",
	"documentConflicts",
	"evidenceIds",
	"modelSuppliedUrls",
	"selectedInputIds",
	"sentenceIds",
	"writingBlockIds",
)

private fun repairIncompleteJson(value: String): String? {
	val stack = ArrayDeque<Char>()
	var inString = false
	var escaped = false
	for (character in value) {
		if (inString) {
			when {
				escaped -> escaped = false
				character == '\\' -> escaped = true
				character == '"' -> inString = false
			}
			continue
		}
		when (character) {
			'"' -> inString = true
			'{' -> stack.addLast('}')
			'[' -> stack.addLast(']')
			'}', ']' -> if (stack.removeLastOrNull() != character) return null
		}
	}
	if (inString) return null
	return buildString {
		append(value.trimEnd().removeSuffix(","))
		while (stack.isNotEmpty()) append(stack.removeLast())
	}
}

object ModelSchemas {
	val WRITER = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,"required":["sentences"],"properties":{"sentences":{"type":"array","minItems":1,"maxItems":6,"items":{"type":"object","additionalProperties":false,"required":["body","intent","conflictEvidenceIds"],"properties":{"body":{"type":"string","minLength":1},"intent":{"type":"string","enum":["FACTUAL","EDITORIAL","UNRESOLVED_CONFLICT"]},"conflictEvidenceIds":{"type":"array","items":{"type":"string","format":"uuid"}}}}}}}"""
	val REVIEWER = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,"required":["reviews"],"properties":{"reviews":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,"required":["sentenceId","verdict","evidenceIds","reason","modelSuppliedUrls"],"properties":{"sentenceId":{"type":"string","format":"uuid"},"verdict":{"type":"string","enum":["SUPPORTED","NOT_REQUIRED","NEEDS_SUPPORT","CONFLICT"]},"evidenceIds":{"type":"array","items":{"type":"string","format":"uuid"}},"reason":{"type":["string","null"]},"modelSuppliedUrls":{"type":"array","items":{"type":"string"}}}}},"documentConflicts":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["sentenceIds","evidenceIds","reason"],"properties":{"sentenceIds":{"type":"array","items":{"type":"string","format":"uuid"}},"evidenceIds":{"type":"array","items":{"type":"string","format":"uuid"}},"reason":{"type":"string","minLength":1}}}}}}"""
	val REWRITE = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,"required":["rewrites"],"properties":{"rewrites":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,"required":["sentenceId","body","omit"],"properties":{"sentenceId":{"type":"string","format":"uuid"},"body":{"type":["string","null"]},"omit":{"type":"boolean"}}}}}}"""

	fun forRole(role: ModelRole): String = when (role) {
		ModelRole.WRITER -> WRITER
		ModelRole.REVIEWER -> REVIEWER
		ModelRole.REWRITER -> REWRITE
	}

	fun promptedInstruction(role: ModelRole): String = when (role) {
		ModelRole.WRITER -> """Return only one JSON result, not a schema or explanation: {"sentences":[{"body":"Customer-facing change","intent":"FACTUAL","conflictEvidenceIds":[]}]}"""
		ModelRole.REVIEWER -> """Return only one JSON result, not a schema or explanation: {"reviews":[{"sentenceId":"copy an exact supplied sentence ID","verdict":"SUPPORTED","evidenceIds":[],"reason":null,"modelSuppliedUrls":[]}]}"""
		ModelRole.REWRITER -> """Return only one JSON result, not a schema or explanation: {"rewrites":[{"sentenceId":"copy an exact supplied sentence ID","body":"Supported rewrite","omit":false}]}"""
	}
}

data class StructuredChatRequest(
	val role: ModelRole,
	val prompt: ChangelogPrompt,
	/** Intentionally empty: artifact workflow models are never granted tools. */
	val toolCallbacks: List<Nothing> = emptyList(),
)

data class StructuredTransportResponse<T : Any>(
	val value: T,
	val responseId: String?,
	val actualModel: String?,
	val finishReason: String?,
	val promptTokens: Int?,
	val completionTokens: Int?,
	val totalTokens: Int?,
)

interface StructuredChatTransport {
	fun <T : Any> exchange(request: StructuredChatRequest, responseType: Class<T>): StructuredTransportResponse<T>
}

class TransientModelTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class NonTransientModelTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class MalformedModelOutputException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class SpringAiOpenAiArtifactWorkflowGateway(
	private val transport: StructuredChatTransport,
	private val properties: PlotAiProperties,
	private val promptFactory: ChangelogPromptFactory,
) : ArtifactWorkflowModelGateway {
	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> = invoke(
		role = ModelRole.WRITER,
		prompt = promptFactory.writer(request.instruction, request.evidence),
		responseType = WriterOutput::class.java,
	)

	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> = invoke(
		role = ModelRole.REVIEWER,
		prompt = promptFactory.reviewer(request),
		responseType = ReviewerOutput::class.java,
	)

	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> = invoke(
		role = ModelRole.REWRITER,
		prompt = promptFactory.rewriter(request),
		responseType = TargetedRewriteOutput::class.java,
	)

	private fun <T : Any> invoke(role: ModelRole, prompt: ChangelogPrompt, responseType: Class<T>): ModelCallResult<T> {
		val startedAt = Instant.now()
		try {
			val response = transport.exchange(StructuredChatRequest(role, prompt), responseType)
			return ModelCallResult(
				response.value,
				response.toMetadata(Duration.between(startedAt, Instant.now())),
			)
		} catch (failure: TransientModelTransportException) {
			throw ArtifactWorkflowModelException(
				ModelFailureCode.PROVIDER_UNAVAILABLE,
				"The model provider is temporarily unavailable",
				failure,
			)
		} catch (failure: MalformedModelOutputException) {
			throw ArtifactWorkflowModelException(
				ModelFailureCode.MALFORMED_OUTPUT,
				"The model returned invalid structured output",
				failure,
			)
		} catch (failure: NonTransientModelTransportException) {
			throw ArtifactWorkflowModelException(
				ModelFailureCode.PROVIDER_REJECTED,
				"The model provider rejected the request",
				failure,
			)
		}
	}

	private fun StructuredTransportResponse<*>.toMetadata(latency: Duration) = ModelCallMetadata(
		responseId = responseId,
		actualModel = actualModel,
		finishReason = finishReason,
		promptTokens = promptTokens,
		completionTokens = completionTokens,
		totalTokens = totalTokens,
		latency = latency,
		observationAttributes = mapOf(
			"gateway" to PlotAiProperties.OPENROUTER_GATEWAY,
			"requestedModel" to requireNotNull(properties.model),
			"servedModel" to actualModel.orEmpty(),
			"responseId" to responseId.orEmpty(),
			"finishReason" to finishReason.orEmpty(),
		),
		gateway = PlotAiProperties.OPENROUTER_GATEWAY,
		requestedModel = properties.model,
	)
}

class SpringAiStructuredChatTransport(
	builder: ChatClient.Builder,
	private val properties: PlotAiProperties,
	private val objectMapper: ObjectMapper = ObjectMapper(),
	private val apiKey: String? = null,
) : StructuredChatTransport {
	private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
	internal val writerClient: ChatClient = builder.clone().build()
	internal val reviewerClient: ChatClient = builder.clone().build()
	private val optionsByRole = ModelRole.entries.associateWith(::buildOptions)

	internal fun optionsFor(role: ModelRole): ChatOptions = optionsByRole.getValue(role)

	private fun buildOptions(role: ModelRole): ChatOptions {
		val builder = OpenAiChatOptions.builder()
			.baseUrl(properties.baseUrl)
			.model(requireNotNull(properties.model))
			.maxCompletionTokens(properties.maxOutputTokens)
			.timeout(properties.timeout)
			.maxRetries(0)
			.customHeaders(mapOf(
				"X-OpenRouter-Metadata" to "enabled",
				"X-OpenRouter-Title" to "Plot",
			))
			.extraBody(properties.openRouterExtraBody)
		if (properties.supportsNativeStructuredOutput) {
			builder.outputSchema(ModelSchemas.forRole(role))
		}
		if (properties.supportsTemperature) {
			builder.temperature(if (role == ModelRole.REVIEWER) properties.reviewerTemperature else properties.writerTemperature)
		}
		return builder.build()
	}

	override fun <T : Any> exchange(request: StructuredChatRequest, responseType: Class<T>): StructuredTransportResponse<T> {
		if (!properties.supportsNativeStructuredOutput) return exchangePromptedJson(request, responseType)
		val client = if (request.role == ModelRole.REVIEWER) reviewerClient else writerClient
		try {
			val systemPrompt = if (properties.supportsNativeStructuredOutput) {
				request.prompt.system
			} else {
				"${request.prompt.system}\n${ModelSchemas.promptedInstruction(request.role)}"
			}
			val call = client.prompt()
				.system(systemPrompt)
				.user(request.prompt.user)
				.options(optionsFor(request.role).mutate())
				.call()
			val (response, value) = if (properties.supportsNativeStructuredOutput) {
				val entity = call.responseEntity(responseType)
				(entity.response ?: throw MalformedModelOutputException("Structured response metadata is missing")) to
					(entity.entity ?: throw MalformedModelOutputException("Structured response body is missing"))
			} else {
				val response = call.chatResponse()
					?: throw MalformedModelOutputException("Structured response metadata is missing")
				response to objectMapper.readJsonObject(response.result?.output?.text, responseType)
			}
			val metadata = response.metadata
			val usage = metadata.usage
			return StructuredTransportResponse(
				value = value,
				responseId = metadata.id,
				actualModel = metadata.model,
				finishReason = response.result?.metadata?.finishReason,
				promptTokens = usage.promptTokens,
				completionTokens = usage.completionTokens,
				totalTokens = usage.totalTokens,
			)
		} catch (failure: OpenAIRetryableException) {
			throw TransientModelTransportException("OpenAI request failed transiently", failure)
		} catch (failure: OpenAIServiceException) {
			throw NonTransientModelTransportException("OpenAI rejected the request", failure)
		} catch (failure: MalformedModelOutputException) {
			throw failure
		} catch (failure: RuntimeException) {
			if (failure.isStructuredOutputFailure()) {
				throw MalformedModelOutputException("Structured output conversion failed", failure)
			}
			throw NonTransientModelTransportException("OpenAI request failed", failure)
		}
	}

	private fun <T : Any> exchangePromptedJson(
		request: StructuredChatRequest,
		responseType: Class<T>,
	): StructuredTransportResponse<T> {
		val key = apiKey?.takeIf { it.isNotBlank() }
			?: throw NonTransientModelTransportException("OpenRouter API key is missing")
		val body = buildMap<String, Any> {
			put("model", requireNotNull(properties.model))
			put("messages", listOf(
				mapOf(
					"role" to "system",
					"content" to "${request.prompt.system}\n${ModelSchemas.promptedInstruction(request.role)}",
				),
				mapOf("role" to "user", "content" to request.prompt.user),
			))
			put("max_completion_tokens", properties.maxOutputTokens)
			putAll(properties.openRouterExtraBody)
		}
		val httpRequest = HttpRequest.newBuilder(URI.create("${properties.baseUrl}/chat/completions"))
			.timeout(properties.timeout)
			.header("Authorization", "Bearer $key")
			.header("Content-Type", "application/json")
			.header("X-OpenRouter-Metadata", "enabled")
			.header("X-OpenRouter-Title", "Plot")
			.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
			.build()
		val response = try {
			httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
		} catch (failure: InterruptedException) {
			Thread.currentThread().interrupt()
			throw TransientModelTransportException("OpenRouter request was interrupted", failure)
		} catch (failure: RuntimeException) {
			throw TransientModelTransportException("OpenRouter request failed", failure)
		} catch (failure: java.io.IOException) {
			throw TransientModelTransportException("OpenRouter request failed", failure)
		}
		if (response.statusCode() !in 200..299) {
			if (isTransientOpenRouterStatus(response.statusCode())) {
				throw TransientModelTransportException("OpenRouter returned HTTP ${response.statusCode()}")
			}
			throw NonTransientModelTransportException("OpenRouter returned HTTP ${response.statusCode()}")
		}
		val root = try {
			objectMapper.readTree(response.body())
		} catch (failure: RuntimeException) {
			throw MalformedModelOutputException("OpenRouter returned invalid JSON", failure)
		}
		val choice = root.path("choices").path(0)
		val content = choice.path("message").path("content").stringValue()
		val value = try {
			objectMapper.readJsonObject(content, responseType)
		} catch (failure: MalformedModelOutputException) {
			val rootCause = generateSequence<Throwable>(failure) { it.cause }.last()
			logger.warn(
				"Prompted JSON parsing failed: role={}, finishReason={}, contentLength={}, rootCause={}",
				request.role,
				choice.path("finish_reason").stringValue(),
				content?.length ?: 0,
				rootCause::class.qualifiedName,
			)
			throw failure
		}
		val usage = root.path("usage")
		fun count(name: String): Int? = usage.path(name).takeUnless { it.isMissingNode || it.isNull }?.intValue()
		return StructuredTransportResponse(
			value = value,
			responseId = root.path("id").stringValue(),
			actualModel = root.path("model").stringValue(),
			finishReason = choice.path("finish_reason").stringValue(),
			promptTokens = count("prompt_tokens"),
			completionTokens = count("completion_tokens"),
			totalTokens = count("total_tokens"),
		)
	}

	private fun Throwable.isStructuredOutputFailure(): Boolean = generateSequence(this) { it.cause }
		.map { it::class.qualifiedName.orEmpty() }
		.any { name -> name.contains("Json", ignoreCase = true) || name.contains("Conversion", ignoreCase = true) }

	private companion object {
		val logger = LoggerFactory.getLogger(SpringAiStructuredChatTransport::class.java)
	}
}

@Configuration(proxyBeanMethods = false)
class ArtifactWorkflowModelGatewayConfiguration {
	@Bean
	fun artifactWorkflowModelGateway(
		builderProvider: ObjectProvider<ChatClient.Builder>,
		properties: PlotAiProperties,
		promptFactory: ChangelogPromptFactory,
		environment: Environment,
		objectMapper: ObjectMapper,
	): ArtifactWorkflowModelGateway {
		// Do not resolve ChatClient.Builder when artifact workflows are disabled: with
		// spring.ai.model.chat=none its factory exists but intentionally has no ChatModel.
		if (properties.configured) validateRuntimeOpenRouterConfiguration(properties, environment)
		val builder = if (properties.configured) builderProvider.ifAvailable else null
		return if (properties.configured && builder != null) {
			SpringAiOpenAiArtifactWorkflowGateway(SpringAiStructuredChatTransport(
				builder,
				properties,
				objectMapper,
				environment.getProperty("spring.ai.openai.api-key"),
			), properties, promptFactory)
		} else {
			DisabledArtifactWorkflowModelGateway()
		}
	}

	private fun validateRuntimeOpenRouterConfiguration(properties: PlotAiProperties, environment: Environment) {
		require(environment.getProperty("spring.ai.openai.base-url", properties.baseUrl) == PlotAiProperties.OPENROUTER_BASE_URL) {
			"spring.ai.openai.base-url must match the canonical OpenRouter API origin"
		}
		val frameworkRetries = environment.getProperty(
			"spring.ai.openai.chat.max-retries",
			Int::class.java,
			environment.getProperty("spring.ai.openai.max-retries", Int::class.java, 0),
		)
		require(frameworkRetries == 0) { "Spring AI framework retries must remain disabled" }
		val loggingKeys = listOf(
			"spring.ai.chat.observations.log-prompt",
			"spring.ai.chat.observations.log-completion",
			"spring.ai.chat.observations.include-error-logging",
			"spring.ai.chat.client.observations.log-prompt",
			"spring.ai.chat.client.observations.log-completion",
			"spring.ai.chat.client.observations.include-error-logging",
		)
		require(loggingKeys.none { environment.getProperty(it, Boolean::class.java, false) }) {
			"Spring AI prompt and completion logging must remain disabled"
		}
	}
}
