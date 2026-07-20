package com.plot.api.ai.provider

import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.SentenceArtifact
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.WriterOutput
import java.time.Duration
import java.util.UUID

interface GenerationModelGateway {
	fun write(request: WriterModelRequest): ModelCallResult<WriterOutput>
	fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput>
	fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput>
}

data class WriterModelRequest(
	val generationRunId: UUID,
	val instruction: String?,
	val evidence: List<EvidenceSnapshot>,
)

data class ReviewerModelRequest(
	val generationRunId: UUID,
	val sentences: List<SentenceArtifact>,
	val evidence: List<EvidenceSnapshot>,
	val resolutionInstruction: String? = null,
)

data class RewriteModelRequest(
	val generationRunId: UUID,
	val sentences: List<SentenceArtifact>,
	val targetSentenceIds: List<UUID>,
	val evidence: List<EvidenceSnapshot>,
	val resolutionInstruction: String?,
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
	/** Physical provider exchanges represented by this logical call, including successful retries. */
	val physicalCallCount: Int = 1,
) {
	init {
		require(physicalCallCount > 0) { "physicalCallCount must be positive" }
	}

	val servedModel: String?
		get() = actualModel
}

enum class ModelFailureCode {
	MODEL_NOT_CONFIGURED,
	PROVIDER_UNAVAILABLE,
	PROVIDER_REJECTED,
	MALFORMED_OUTPUT,
}

class GenerationModelException(
	val code: ModelFailureCode,
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

enum class ModelRole { WRITER, REVIEWER, REWRITER }
