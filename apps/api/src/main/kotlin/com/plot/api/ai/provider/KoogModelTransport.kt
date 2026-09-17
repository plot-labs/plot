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
		properties, objectMapper, if (properties.configured) JavaKoogHttpClient.Factory().create(
			clientName = "PlotOpenRouter",
			baseUrl = "https://openrouter.ai",
			headers = mapOf(
				"Authorization" to "Bearer ${requireNotNull(environment.getProperty("plot.ai.api-key")?.takeIf(String::isNotBlank)) { "plot.ai.api-key is required when AI is enabled" }}",
				"X-OpenRouter-Metadata" to "enabled",
				"X-OpenRouter-Title" to "Plot",
			),
			requestTimeoutMillis = properties.timeout.toMillis(),
			connectTimeoutMillis = minOf(properties.timeout.toMillis(), 10_000),
			socketTimeoutMillis = properties.timeout.toMillis(),
		) else null)

	override fun <T : Any> exchange(request: StructuredChatRequest, responseType: Class<T>): StructuredTransportResponse<T> =
		exchange(request.prompt.system, request.prompt.user, ModelSchemas.forRole(request.role), responseType,
			properties.maxOutputTokens, if (properties.supportsTemperature) {
				if (request.role == ModelRole.REVIEWER) properties.reviewerTemperature else properties.writerTemperature
			} else null)

	fun <T : Any> exchange(system: String, user: String, schema: String, responseType: Class<T>,
		maxTokens: Int, temperature: Double?): StructuredTransportResponse<T> {
		val captured = ResponseMetadataClient(client ?: throw NonTransientModelTransportException("Model is not configured"), objectMapper)
		val configuredClient = OpenRouterLLMClient(httpClient = captured)
		val response = try {
			runBlocking {
				withTimeout(properties.timeout.toMillis()) {
					configuredClient.execute(prompt("plot-structured", OpenRouterParams(
						maxTokens = minOf(maxTokens, properties.maxOutputTokens),
						temperature = temperature,
						schema = LLMParams.Schema.JSON.Standard("plot_output", Json.parseToJsonElement(schema).jsonObject),
						additionalProperties = mapOf("provider" to Json.parseToJsonElement(
							objectMapper.writeValueAsString(properties.openRouterProviderPolicy))),
					)) { system(system); user(user) }, LLModel(LLMProvider.OpenRouter, requireNotNull(properties.model),
						listOf(LLMCapability.Completion, LLMCapability.Schema.JSON.Standard)))
				}
			}
		} catch (failure: MalformedModelOutputException) {
			throw failure
		} catch (failure: Exception) {
			val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
			causes.filterIsInstance<MalformedModelOutputException>().firstOrNull()?.let { throw it }
			causes.filterIsInstance<TransientModelTransportException>().firstOrNull()?.let { throw it }
			val http = causes.filterIsInstance<KoogHttpClientException>().firstOrNull()
			if (http?.statusCode in listOf(408, 429) || (http?.statusCode ?: 0) >= 500 ||
				causes.any { it is java.io.IOException || it is kotlinx.coroutines.TimeoutCancellationException }) {
				throw TransientModelTransportException("Model provider unavailable")
			}
			// Provider exception bodies can contain private source text; do not attach them to exported errors.
			throw NonTransientModelTransportException("Model provider rejected the request")
		}
		if (response.finishReason != "stop") throw MalformedModelOutputException("Model output did not finish normally")
		val value = try { objectMapper.readValue(response.textContent(), responseType) }
			catch (_: Exception) { throw MalformedModelOutputException("Invalid structured model output") }
		return StructuredTransportResponse(value, captured.responseId, captured.model, response.finishReason,
			response.metaInfo.inputTokensCount, response.metaInfo.outputTokensCount, response.metaInfo.totalTokensCount)
	}

	internal fun agentModel(model: String? = null) = LLModel(LLMProvider.OpenRouter, model ?: requireNotNull(properties.model),
		listOf(LLMCapability.Completion, LLMCapability.Tools))

	internal fun agentParams(routingProvider: String? = null, reasoningEffort: String? = null): OpenRouterParams {
		val additionalProperties = mutableMapOf(
			"provider" to Json.parseToJsonElement(objectMapper.writeValueAsString(
				routingProvider?.let(properties::openRouterProviderPolicyFor) ?: properties.openRouterProviderPolicy,
			)),
		)
		reasoningEffort?.let { effort ->
			additionalProperties["reasoning"] = Json.parseToJsonElement(
				objectMapper.writeValueAsString(mapOf("effort" to effort)),
			)
		}
		return OpenRouterParams(maxTokens = properties.maxOutputTokens, additionalProperties = additionalProperties)
	}

	internal suspend fun exchangeAgent(prompt: ai.koog.prompt.Prompt, model: LLModel,
		tools: List<ai.koog.agents.core.tools.ToolDescriptor>): ai.koog.prompt.message.Message.Assistant = try {
		withTimeout(properties.timeout.toMillis()) {
			OpenRouterLLMClient(httpClient = ResponseMetadataClient(requireNotNull(client), objectMapper))
				.execute(prompt, model, tools).also {
					if (it.finishReason !in listOf("stop", "tool_calls")) {
						throw MalformedModelOutputException("Agent output did not finish normally")
					}
				}
		}
	} catch (failure: Exception) {
		val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
		val http = causes.filterIsInstance<KoogHttpClientException>().firstOrNull()
		val transient = http?.statusCode in listOf(408, 429) || (http?.statusCode ?: 0) >= 500 ||
			causes.any { it is java.io.IOException || it is kotlinx.coroutines.TimeoutCancellationException || it is TransientModelTransportException }
		throw AgentDecisionException(if (transient) "PROVIDER_UNAVAILABLE" else "PROVIDER_REJECTED", transient, "Agent provider request failed")
	}

	/** Koog 1.2 drops OpenRouter response ID/model; capture only these allowlisted fields per call. */
	private class ResponseMetadataClient(private val delegate: KoogHttpClient, private val mapper: ObjectMapper) : KoogHttpClient by delegate {
		var responseId: String? = null
		var model: String? = null
		override suspend fun <T : Any, R : Any> post(path: String, requestBody: T, requestBodyType: KClass<T>,
			responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R {
			val response = delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)
			if (response is String) {
				val root = try { mapper.readTree(response) } catch (_: Exception) {
					throw MalformedModelOutputException("Invalid provider response")
				}
				if (root.has("error")) {
					val code = root["error"].get("code")?.intValue() ?: 0
					if (code == 429 || code == 408 || code >= 500) throw TransientModelTransportException("Model provider unavailable")
					throw NonTransientModelTransportException("Model provider rejected the request")
				}
				val choices = root.get("choices")
				if (choices == null || !choices.isArray || choices.size() != 1) {
					throw MalformedModelOutputException("Expected one model choice")
				}
				responseId = root.get("id")?.stringValue()
				model = root.get("model")?.stringValue()
			}
			return response
		}
	}

	@PreDestroy
	fun close() { client?.close() }
}
