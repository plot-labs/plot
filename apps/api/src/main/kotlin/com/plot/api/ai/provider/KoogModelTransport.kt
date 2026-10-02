package com.plot.api.ai.provider

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterParams
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.java.JavaKoogHttpClient
import ai.koog.prompt.llm.LLMCapability
import kotlin.reflect.KClass
import ai.koog.prompt.params.LLMParams
import com.plot.api.config.PlotAiProperties
import jakarta.annotation.PreDestroy
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Shared structured-output and native-tool transport. Koog retries and content tracing are not installed. */
@Component
class KoogModelTransport internal constructor(
	private val properties: PlotAiProperties,
	private val objectMapper: ObjectMapper,
	private val client: KoogHttpClient?,
) : StructuredChatTransport {
	@org.springframework.beans.factory.annotation.Autowired
	constructor(properties: PlotAiProperties, objectMapper: ObjectMapper, environment: Environment) : this(
		properties, objectMapper, if (properties.configured) configuredClient(properties, environment) else null)

	private companion object {
		fun configuredClient(properties: PlotAiProperties, environment: Environment): KoogHttpClient {
			val headers = mapOf(
				"Authorization" to "Bearer ${requireNotNull(environment.getProperty("plot.ai.api-key")?.takeIf(String::isNotBlank)) { "plot.ai.api-key is required when AI is enabled" }}",
				"X-OpenRouter-Metadata" to "enabled", "X-OpenRouter-Title" to "Plot",
			)
			return StreamingHttpClient(JavaKoogHttpClient.Factory().create(
			clientName = "PlotOpenRouter",
			baseUrl = "https://openrouter.ai",
			headers = headers,
			requestTimeoutMillis = properties.timeout.toMillis(),
			connectTimeoutMillis = minOf(properties.timeout.toMillis(), 10_000),
			socketTimeoutMillis = properties.timeout.toMillis(),
		), "https://openrouter.ai", headers, properties.timeout)
		}
	}

	override fun <T : Any> exchange(request: StructuredChatRequest, responseType: Class<T>): StructuredTransportResponse<T> =
		exchange(request.prompt.system, request.prompt.user, ModelSchemas.forRole(request.role), responseType,
			properties.maxOutputTokens, if (properties.supportsTemperature) {
				if (request.role == ModelRole.REVIEWER) properties.reviewerTemperature else properties.writerTemperature
			} else null, request.onText)

	fun <T : Any> exchange(system: String, user: String, schema: String, responseType: Class<T>,
		maxTokens: Int, temperature: Double?, onText: ((String) -> Unit)? = null): StructuredTransportResponse<T> {
		val captured = ResponseMetadataClient(client ?: throw NonTransientModelTransportException("Model is not configured"), objectMapper)
		val configuredClient = OpenRouterLLMClient(httpClient = captured)
		val response = try {
			runBlocking {
				withTimeout(properties.timeout.toMillis()) {
					val input = prompt("plot-structured", OpenRouterParams(
						maxTokens = minOf(maxTokens, properties.maxOutputTokens),
						temperature = temperature,
						schema = LLMParams.Schema.JSON.Standard("plot_output", Json.parseToJsonElement(schema).jsonObject),
						additionalProperties = mapOf(
							"provider" to Json.parseToJsonElement(objectMapper.writeValueAsString(properties.openRouterProviderPolicy)),
							"usage" to Json.parseToJsonElement("""{"include":true}"""),
						),
					)) { system(system); user(user) }
					val model = LLModel(LLMProvider.OpenRouter, requireNotNull(properties.model),
						listOf(LLMCapability.Completion, LLMCapability.Schema.JSON.Standard))
					if (onText == null) configuredClient.execute(input, model)
					else collectStream(configuredClient, input, model, emptyList(), onText, 400_000)
				}
			}
		} catch (failure: MalformedModelOutputException) {
			throw MalformedModelOutputException(failure.message.orEmpty(), usage = failure.usage ?: captured.failureUsage(properties.model))
		} catch (failure: Exception) {
			val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
			causes.filterIsInstance<ModelProgressFailure>().firstOrNull()?.let { throw requireNotNull(it.cause) }
			causes.filterIsInstance<MalformedModelOutputException>().firstOrNull()?.let {
				throw MalformedModelOutputException(it.message.orEmpty(), usage = it.usage ?: captured.failureUsage(properties.model))
			}
			causes.filterIsInstance<TransientModelTransportException>().firstOrNull()?.let {
				throw TransientModelTransportException(it.message.orEmpty(), usage = captured.failureUsage(properties.model))
			}
			val http = causes.filterIsInstance<KoogHttpClientException>().firstOrNull()
			if (http?.statusCode in listOf(408, 429) || (http?.statusCode ?: 0) >= 500 ||
				causes.any { it is java.io.IOException || it is kotlinx.coroutines.TimeoutCancellationException }) {
				throw TransientModelTransportException("Model provider unavailable", usage = captured.failureUsage(properties.model).takeIf { http == null })
			}
			// Provider exception bodies can contain private source text; do not attach them to exported errors.
			throw NonTransientModelTransportException("Model provider rejected the request", usage = captured.failureUsage(properties.model).takeIf { http == null })
		}
		val usage = captured.toUsage(properties.model)
		if (response.finishReason != "stop") throw MalformedModelOutputException("Model output did not finish normally", usage = usage)
		val value = try { objectMapper.readValue(response.textContent(), responseType) }
			catch (_: Exception) { throw MalformedModelOutputException("Invalid structured model output", usage = usage) }
		return StructuredTransportResponse(value, captured.responseId, captured.model, response.finishReason,
			usage.inputTokens?.toModelMetadataInt(), usage.outputTokens?.toModelMetadataInt(), usage.totalTokens?.toModelMetadataInt(),
			usage.cacheReadTokens, usage.cacheWriteTokens, usage.reasoningTokens, usage.reportedCostUsd)
	}

	internal fun agentModel(model: String? = null) = LLModel(LLMProvider.OpenRouter, model ?: requireNotNull(properties.model),
		listOf(LLMCapability.Completion, LLMCapability.Tools))

	internal fun agentParams(routingProvider: String? = null, reasoningEffort: String? = null): OpenRouterParams {
		val additionalProperties = mutableMapOf(
			"provider" to Json.parseToJsonElement(objectMapper.writeValueAsString(
				routingProvider?.let(properties::openRouterProviderPolicyFor) ?: properties.openRouterProviderPolicy,
			)),
			"usage" to Json.parseToJsonElement("""{"include":true}"""),
		)
		reasoningEffort?.let { effort ->
			additionalProperties["reasoning"] = Json.parseToJsonElement(
				objectMapper.writeValueAsString(mapOf("effort" to effort)),
			)
		}
		return OpenRouterParams(maxTokens = properties.maxOutputTokens, additionalProperties = additionalProperties)
	}

	internal suspend fun exchangeAgent(prompt: ai.koog.prompt.Prompt, model: LLModel,
		tools: List<ai.koog.agents.core.tools.ToolDescriptor>, onText: (String) -> Unit = {}): AgentModelResponse {
		val captured = ResponseMetadataClient(requireNotNull(client), objectMapper)
		return try {
			withTimeout(properties.timeout.toMillis()) {
				OpenRouterLLMClient(httpClient = captured)
					.let { collectStream(it, prompt, model, tools, onText) }.also {
						if (it.finishReason !in listOf("stop", "tool_calls")) {
							throw MalformedModelOutputException("Agent output did not finish normally", usage = captured.toUsage(model.id))
						}
					}.let { AgentModelResponse(it, captured.toUsage(model.id)) }
			}
		} catch (failure: Exception) {
			val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
			causes.filterIsInstance<ModelProgressFailure>().firstOrNull()?.let { throw requireNotNull(it.cause) }
			causes.filterIsInstance<MalformedModelOutputException>().firstOrNull()?.let {
				throw AgentDecisionException("AGENT_INVALID_RESPONSE", false, "Agent provider response was invalid", usage = it.usage ?: captured.failureUsage(model.id))
			}
			val http = causes.filterIsInstance<KoogHttpClientException>().firstOrNull()
			val transient = http?.statusCode in listOf(408, 429) || (http?.statusCode ?: 0) >= 500 ||
				causes.any { it is java.io.IOException || it is kotlinx.coroutines.TimeoutCancellationException || it is TransientModelTransportException }
			throw AgentDecisionException(if (transient) "PROVIDER_UNAVAILABLE" else "PROVIDER_REJECTED", transient, "Agent provider request failed", usage = captured.failureUsage(model.id).takeIf { http == null })
		}
	}

	private suspend fun collectStream(
		client: OpenRouterLLMClient, prompt: ai.koog.prompt.Prompt, model: LLModel,
		tools: List<ai.koog.agents.core.tools.ToolDescriptor>, onText: (String) -> Unit, textLimit: Int = 40_000,
	): ai.koog.prompt.message.Message.Assistant {
		val completed = mutableListOf<StreamFrame>()
		var characters = 0
		val text = StringBuilder()
		client.executeStreaming(prompt, model, tools).collect { frame ->
			when (frame) {
				is StreamFrame.TextDelta -> {
					characters += frame.text.length
					if (text.length + frame.text.length > textLimit) throw MalformedModelOutputException("Chat output exceeded limit")
					text.append(frame.text)
					try { if (frame.text.isNotEmpty()) onText(frame.text) } catch (failure: Exception) { throw ModelProgressFailure(failure) }
				}
				is StreamFrame.ToolCallDelta -> characters += frame.content.orEmpty().length
				is StreamFrame.ReasoningDelta -> characters += frame.text.orEmpty().length + frame.summary.orEmpty().length
				is StreamFrame.TextComplete -> Unit
				else -> completed.add(frame)
			}
			if (characters > 400_000) throw MalformedModelOutputException("Model output exceeded limit")
		}
		// Koog textContent inserts separators between text parts split by reasoning frames.
		if (text.isNotEmpty()) completed.add(StreamFrame.TextComplete(text.toString()))
		return completed.toMessageResponse()
	}

	/** Koog 1.2 drops OpenRouter response ID/model; capture only these allowlisted fields per call. */
	private class ResponseMetadataClient(private val delegate: KoogHttpClient, private val mapper: ObjectMapper) : KoogHttpClient by delegate {
		var responseId: String? = null
		var model: String? = null
		var inputTokens: Long? = null
		var outputTokens: Long? = null
		var totalTokens: Long? = null
		var cacheReadTokens: Long? = null
		var cacheWriteTokens: Long? = null
		var reasoningTokens: Long? = null
		var reportedCostUsd: java.math.BigDecimal? = null
		private var streaming = false
		private var streamCharacters = 0
		private var streamFrames = 0
		private var retainedCharacters = 0
		override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: KClass<T>,
			responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R {
			val response = delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)
			if (response is String) capture(response, streaming = false)
			return response
		}

		private fun capture(response: String, streaming: Boolean) {
			if (streaming) {
				streamCharacters += response.length
				if (++streamFrames > 4_096 || streamCharacters > 800_000) throw MalformedModelOutputException("Provider stream exceeded limit")
			}
			val root = try { mapper.readTree(response) } catch (_: Exception) {
				throw MalformedModelOutputException("Invalid provider response")
			}
			if (root.has("error")) {
				val rawCode = root["error"].get("code")?.toString()?.trim('"')
				val code = rawCode?.toIntOrNull() ?: if (rawCode == "server_error") 503 else 0
				if (code == 429 || code == 408 || code >= 500) throw TransientModelTransportException("Model provider unavailable")
				throw NonTransientModelTransportException("Model provider rejected the request")
			}
			val choices = root.get("choices")
			if (choices == null || !choices.isArray || (choices.size() != 1 && !(streaming && choices.isEmpty()))) {
				throw MalformedModelOutputException("Expected one model choice")
			}
			if (streaming) {
				val delta = root.path("choices").path(0).path("delta")
				// Bound all strings before Koog assembles tool/reasoning frames.
				fun size(node: tools.jackson.databind.JsonNode): Int = when {
					node.isString -> node.stringValue().length
					node.isContainer -> node.sumOf(::size)
					else -> 0
				}
				retainedCharacters += size(delta)
				if (retainedCharacters > 400_000) throw MalformedModelOutputException("Model output exceeded limit")
			}
			responseId = root.get("id")?.stringValue() ?: responseId
			model = root.get("model")?.stringValue() ?: model
			val usage = root.path("usage")
			inputTokens = usage.integral("prompt_tokens") ?: inputTokens
			outputTokens = usage.integral("completion_tokens") ?: outputTokens
			totalTokens = usage.integral("total_tokens") ?: totalTokens
			val inputDetails = usage.path("prompt_tokens_details")
			cacheReadTokens = inputDetails.integral("cached_tokens") ?: cacheReadTokens
			cacheWriteTokens = inputDetails.integral("cache_write_tokens") ?: cacheWriteTokens
			reasoningTokens = usage.path("completion_tokens_details").integral("reasoning_tokens") ?: reasoningTokens
			reportedCostUsd = usage.path("cost").takeUnless { it.isMissingNode || it.isNull }?.let {
				runCatching { it.decimalValue() }.getOrNull()
			} ?: reportedCostUsd
		}

		override fun <T : Any, R : Any, O : Any> sse(path: String, requestBody: T, requestBodyType: KClass<T>,
			dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R, processStreamingChunk: (R) -> O?,
			parameters: Map<String, String>, headers: Map<String, String>): Flow<O> {
			streaming = true
			return delegate.sse(path, requestBody, requestBodyType, dataFilter,
				{ data -> capture(data, streaming = true); decodeStreamingResponse(data) }, processStreamingChunk, parameters, headers)
		}

		fun failureUsage(model: String?): ProviderUsage? = if (streaming) toUsage(model) else null

		fun toUsage(requestedModel: String?) = ProviderUsage(
			provider = PlotAiProperties.OPENROUTER_GATEWAY,
			requestedModel = requestedModel,
			actualModel = model,
			responseId = responseId,
			inputTokens = inputTokens,
			outputTokens = outputTokens,
			cacheReadTokens = cacheReadTokens,
			cacheWriteTokens = cacheWriteTokens,
			reasoningTokens = reasoningTokens,
			totalTokens = totalTokens,
			reportedCostUsd = reportedCostUsd,
		)

		private fun tools.jackson.databind.JsonNode.integral(field: String): Long? =
			path(field).takeIf { it.isIntegralNumber }?.longValue()
	}

	@PreDestroy
	fun close() { client?.close() }
}

private class ModelProgressFailure(cause: Exception) : RuntimeException(null, cause, false, false)
