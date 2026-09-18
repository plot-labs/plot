package com.plot.api.ai.provider

import com.plot.api.ai.prompt.ArtifactPrompt
import com.plot.api.config.PlotAiProperties
import com.plot.api.content.ContentTypeRegistry
import com.plot.api.content.FrozenContentContextLookup
import com.plot.api.content.FrozenPromptVersionLookup
import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import com.plot.api.artifact.workflow.model.WriterOutput
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

object ModelSchemas {
	val WRITER = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,"required":["sentences","layout"],"properties":{"sentences":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,"required":["body","intent","conflictEvidenceIds"],"properties":{"body":{"type":"string","minLength":1},"intent":{"type":"string","enum":["FACTUAL","EDITORIAL","UNRESOLVED_CONFLICT"]},"conflictEvidenceIds":{"type":"array","items":{"type":"string","format":"uuid"}}}}},"layout":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/layoutNode"}}},"${'$'}defs":{"layoutNode":{"type":"object","additionalProperties":false,"required":["type","statementIndex","tag","listType","start","children"],"properties":{"type":{"type":"string","enum":["heading","paragraph","list","listItem"]},"statementIndex":{"type":["integer","null"],"minimum":0},"tag":{"type":["string","null"],"enum":["h1","h2","h3",null]},"listType":{"type":["string","null"],"enum":["bullet","ordered",null]},"start":{"type":["integer","null"],"minimum":1},"children":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/layoutNode"}}}}}}"""
	val REVIEWER = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,"required":["reviews","documentConflicts"],"properties":{"reviews":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,"required":["sentenceId","verdict","evidenceIds","reason","modelSuppliedUrls"],"properties":{"sentenceId":{"type":"string","format":"uuid"},"verdict":{"type":"string","enum":["SUPPORTED","NOT_REQUIRED","NEEDS_SUPPORT","CONFLICT"]},"evidenceIds":{"type":"array","items":{"type":"string","format":"uuid"}},"reason":{"type":["string","null"]},"modelSuppliedUrls":{"type":"array","items":{"type":"string"}}}}},"documentConflicts":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["sentenceIds","evidenceIds","reason"],"properties":{"sentenceIds":{"type":"array","items":{"type":"string","format":"uuid"}},"evidenceIds":{"type":"array","items":{"type":"string","format":"uuid"}},"reason":{"type":"string","minLength":1}}}}}}"""
	val REWRITE = """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,"required":["rewrites"],"properties":{"rewrites":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,"required":["sentenceId","body","omit"],"properties":{"sentenceId":{"type":"string","format":"uuid"},"body":{"type":["string","null"]},"omit":{"type":"boolean"}}}}}}"""

	fun forRole(role: ModelRole): String = when (role) {
		ModelRole.WRITER -> WRITER
		ModelRole.REVIEWER -> REVIEWER
		ModelRole.REWRITER -> REWRITE
	}
}

data class StructuredChatRequest(
	val role: ModelRole,
	val prompt: ArtifactPrompt,
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
	val cacheReadTokens: Long? = null,
	val cacheWriteTokens: Long? = null,
	val reasoningTokens: Long? = null,
	val reportedCostUsd: java.math.BigDecimal? = null,
)

interface StructuredChatTransport {
	fun <T : Any> exchange(request: StructuredChatRequest, responseType: Class<T>): StructuredTransportResponse<T>
}

class TransientModelTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class NonTransientModelTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class MalformedModelOutputException(
	message: String,
	cause: Throwable? = null,
	val usage: ProviderUsage? = null,
) : RuntimeException(message, cause)

class KoogArtifactWorkflowGateway(
	private val transport: StructuredChatTransport,
	private val properties: PlotAiProperties,
	private val contentTypeRegistry: ContentTypeRegistry,
	private val frozenPromptVersionLookup: FrozenPromptVersionLookup,
	private val frozenContentContextLookup: FrozenContentContextLookup,
) : ArtifactWorkflowModelGateway {
	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> {
		val promptFactory = promptFactoryFor(request.artifactWorkflowRunId)
		return invoke(
			role = ModelRole.WRITER,
			prompt = promptFactory.writer(
				request.instruction,
				request.evidence,
				frozenContentContextLookup.forRun(request.artifactWorkflowRunId),
				request.documentVersion,
			),
			responseType = WriterOutput::class.java,
		)
	}

	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> {
		val promptFactory = promptFactoryFor(request.artifactWorkflowRunId)
		return invoke(
			role = ModelRole.REVIEWER,
			prompt = promptFactory.reviewer(request),
			responseType = ReviewerOutput::class.java,
		)
	}

	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> {
		val promptFactory = promptFactoryFor(request.artifactWorkflowRunId)
		return invoke(
			role = ModelRole.REWRITER,
			prompt = promptFactory.rewriter(request),
			responseType = TargetedRewriteOutput::class.java,
		)
	}

	private fun promptFactoryFor(artifactWorkflowRunId: UUID) =
		contentTypeRegistry.promptFactoryFor(frozenPromptVersionLookup.promptVersionFor(artifactWorkflowRunId))

	private fun <T : Any> invoke(role: ModelRole, prompt: ArtifactPrompt, responseType: Class<T>): ModelCallResult<T> {
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
		cacheReadTokens = cacheReadTokens,
		cacheWriteTokens = cacheWriteTokens,
		reasoningTokens = reasoningTokens,
		reportedCostUsd = reportedCostUsd,
	)
}

@Configuration(proxyBeanMethods = false)
class ArtifactWorkflowModelGatewayConfiguration {
	@Bean
	fun artifactWorkflowModelGateway(
		transport: KoogModelTransport,
		properties: PlotAiProperties,
		contentTypeRegistry: ContentTypeRegistry,
		frozenPromptVersionLookup: FrozenPromptVersionLookup,
		frozenContentContextLookup: FrozenContentContextLookup,
	): ArtifactWorkflowModelGateway = if (properties.configured) {
		KoogArtifactWorkflowGateway(transport, properties, contentTypeRegistry, frozenPromptVersionLookup, frozenContentContextLookup)
	} else DisabledArtifactWorkflowModelGateway()
}
