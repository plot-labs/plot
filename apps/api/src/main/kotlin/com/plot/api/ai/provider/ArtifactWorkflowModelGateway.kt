package com.plot.api.ai.provider

import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.SentenceArtifact
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import com.plot.api.artifact.workflow.model.WriterOutput
import java.time.Duration
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
) {
	val servedModel: String?
		get() = actualModel
}

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
) : RuntimeException(message, cause)

enum class ModelRole { WRITER, REVIEWER, REWRITER }
