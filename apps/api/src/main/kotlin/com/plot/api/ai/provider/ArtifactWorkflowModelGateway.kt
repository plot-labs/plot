package com.plot.api.ai.provider

import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.SentenceArtifact
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import com.plot.api.artifact.workflow.model.WriterOutput
import java.time.Duration
import java.math.BigDecimal
import java.util.UUID

interface ArtifactWorkflowModelGateway {
	fun write(request: WriterModelRequest): ModelCallResult<WriterOutput>
	fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput>
	fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput>
}

data class WriterModelRequest(
	val artifactWorkflowRunId: UUID,
	val instruction: String?,
	val evidence: List<EvidenceSnapshot>,
	val documentVersion: Int = 1,
)

data class ReviewerModelRequest(
	val artifactWorkflowRunId: UUID,
	val sentences: List<SentenceArtifact>,
	val evidence: List<EvidenceSnapshot>,
)

data class RewriteModelRequest(
	val artifactWorkflowRunId: UUID,
	val sentences: List<SentenceArtifact>,
	val targetSentenceIds: List<UUID>,
	val evidence: List<EvidenceSnapshot>,
)

data class ModelCallResult<T : Any>(
	val value: T,
	val metadata: ModelCallMetadata,
)

data class ModelCallMetadata(
	val responseId: String?,
	val actualModel: String?,
	val finishReason: String?,
	val promptTokens: Int?,
	val completionTokens: Int?,
	val totalTokens: Int?,
	val latency: Duration,
	/** A deliberately allow-listed metadata projection. Prompt, completion, and evidence bodies never belong here. */
	val observationAttributes: Map<String, String>,
	val gateway: String? = null,
	val requestedModel: String? = null,
	val cacheReadTokens: Long? = null,
	val cacheWriteTokens: Long? = null,
	val reasoningTokens: Long? = null,
	val reportedCostUsd: BigDecimal? = null,
) {
	val servedModel: String?
		get() = actualModel
}

data class ProviderUsage(
	val provider: String?,
	val requestedModel: String?,
	val actualModel: String?,
	val responseId: String?,
	val inputTokens: Long?,
	val outputTokens: Long?,
	val cacheReadTokens: Long?,
	val cacheWriteTokens: Long?,
	val reasoningTokens: Long?,
	val totalTokens: Long?,
	val reportedCostUsd: BigDecimal?,
)

fun ModelCallMetadata.toProviderUsage() = ProviderUsage(
	provider = gateway,
	requestedModel = requestedModel,
	actualModel = actualModel,
	responseId = responseId,
	inputTokens = promptTokens?.toLong(),
	outputTokens = completionTokens?.toLong(),
	cacheReadTokens = cacheReadTokens,
	cacheWriteTokens = cacheWriteTokens,
	reasoningTokens = reasoningTokens,
	totalTokens = totalTokens?.toLong(),
	reportedCostUsd = reportedCostUsd,
)

internal fun Long.toModelMetadataInt(): Int? =
	takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()

enum class ModelFailureCode {
	MODEL_NOT_CONFIGURED,
	PROVIDER_UNAVAILABLE,
	PROVIDER_REJECTED,
	MALFORMED_OUTPUT,
}

class ArtifactWorkflowModelException(
	val code: ModelFailureCode,
	message: String,
	cause: Throwable? = null,
	val metadata: ModelCallMetadata? = null,
) : RuntimeException(message, cause)

enum class ModelRole { WRITER, REVIEWER, REWRITER }
