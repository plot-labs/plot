package com.plot.api.ai.provider

import com.openai.errors.OpenAIRetryableException
import com.openai.errors.OpenAIServiceException
import com.plot.api.ai.prompt.ChangelogPrompt
import com.plot.api.ai.prompt.ChangelogPromptFactory
import com.plot.api.config.PlotAiProperties
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.WriterOutput
import java.time.Duration
import java.time.Instant
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

object ModelSchemas {
	val WRITER = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,"required":["sentences"],"properties":{"sentences":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,"required":["body"],"properties":{"body":{"type":"string","minLength":1}}}}}}"""
	val REVIEWER = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,"required":["reviews"],"properties":{"reviews":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,"required":["sentenceId","verdict","evidenceIds","reason","modelSuppliedUrls"],"properties":{"sentenceId":{"type":"string","format":"uuid"},"verdict":{"type":"string","enum":["SUPPORTED","NOT_REQUIRED","NEEDS_SUPPORT","CONFLICT"]},"evidenceIds":{"type":"array","items":{"type":"string","format":"uuid"},"uniqueItems":true},"reason":{"type":["string","null"]},"modelSuppliedUrls":{"type":"array","items":{"type":"string"}}}}}}}"""
	val REWRITE = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,"required":["rewrites"],"properties":{"rewrites":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,"required":["sentenceId","body"],"properties":{"sentenceId":{"type":"string","format":"uuid"},"body":{"type":"string","minLength":1}}}}}}"""

	fun forRole(role: ModelRole): String = when (role) {
		ModelRole.WRITER -> WRITER
		ModelRole.REVIEWER -> REVIEWER
		ModelRole.REWRITER -> REWRITE
	}
}

data class StructuredChatRequest(
	val role: ModelRole,
	val prompt: ChangelogPrompt,
	/** Intentionally empty: generation models are never granted tools. */
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

class SpringAiOpenAiGenerationGateway(
	private val transport: StructuredChatTransport,
	private val properties: PlotAiProperties,
	private val promptFactory: ChangelogPromptFactory,
	private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : GenerationModelGateway {
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
		var transportFailures = 0
		var schemaFailures = 0
		val startedAt = Instant.now()
		while (true) {
			try {
				val response = transport.exchange(StructuredChatRequest(role, prompt), responseType)
				return ModelCallResult(response.value, response.toMetadata(Duration.between(startedAt, Instant.now())))
			} catch (failure: TransientModelTransportException) {
				if (transportFailures++ >= properties.transportRetries) {
					throw GenerationModelException(ModelFailureCode.PROVIDER_UNAVAILABLE, "The model provider is temporarily unavailable", failure)
				}
				sleep(properties.retryInitialDelay.multipliedBy(1L shl (transportFailures - 1).coerceAtMost(8)))
			} catch (failure: MalformedModelOutputException) {
				if (schemaFailures++ >= properties.schemaRetries) {
					throw GenerationModelException(ModelFailureCode.MALFORMED_OUTPUT, "The model returned invalid structured output", failure)
				}
			} catch (failure: NonTransientModelTransportException) {
				throw GenerationModelException(ModelFailureCode.PROVIDER_REJECTED, "The model provider rejected the request", failure)
			}
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
			"provider" to "openai",
			"responseId" to responseId.orEmpty(),
			"model" to actualModel.orEmpty(),
			"finishReason" to finishReason.orEmpty(),
		),
	)
}

class SpringAiStructuredChatTransport(
	builder: ChatClient.Builder,
	private val properties: PlotAiProperties,
) : StructuredChatTransport {
	internal val writerClient: ChatClient = builder.clone().build()
	internal val reviewerClient: ChatClient = builder.clone().build()

	internal fun optionsFor(role: ModelRole): ChatOptions = OpenAiChatOptions.builder()
		.model(requireNotNull(properties.model))
		.temperature(if (role == ModelRole.REVIEWER) properties.reviewerTemperature else properties.writerTemperature)
		.maxCompletionTokens(properties.maxOutputTokens)
		.timeout(properties.timeout)
		.maxRetries(0)
		.outputSchema(ModelSchemas.forRole(role))
		.build()

	override fun <T : Any> exchange(request: StructuredChatRequest, responseType: Class<T>): StructuredTransportResponse<T> {
		val client = if (request.role == ModelRole.REVIEWER) reviewerClient else writerClient
		try {
			val entity = client.prompt()
				.system(request.prompt.system)
				.user(request.prompt.user)
				.options(optionsFor(request.role).mutate())
				.call()
				.responseEntity(responseType)
			val response = entity.response ?: throw MalformedModelOutputException("Structured response metadata is missing")
			val value = entity.entity ?: throw MalformedModelOutputException("Structured response body is missing")
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

	private fun Throwable.isStructuredOutputFailure(): Boolean = generateSequence(this) { it.cause }
		.map { it::class.qualifiedName.orEmpty() }
		.any { name -> name.contains("Json", ignoreCase = true) || name.contains("Conversion", ignoreCase = true) }
}

@Configuration(proxyBeanMethods = false)
class GenerationModelGatewayConfiguration {
	@Bean
	fun generationModelGateway(
		builderProvider: ObjectProvider<ChatClient.Builder>,
		properties: PlotAiProperties,
		promptFactory: ChangelogPromptFactory,
	): GenerationModelGateway {
		// Do not resolve ChatClient.Builder when generation is disabled: with
		// spring.ai.model.chat=none its factory exists but intentionally has no ChatModel.
		val builder = if (properties.configured) builderProvider.ifAvailable else null
		return if (properties.configured && builder != null) {
			SpringAiOpenAiGenerationGateway(SpringAiStructuredChatTransport(builder, properties), properties, promptFactory)
		} else {
			DisabledGenerationModelGateway()
		}
	}
}
