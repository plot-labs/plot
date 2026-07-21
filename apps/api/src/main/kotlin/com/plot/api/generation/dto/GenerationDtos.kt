package com.plot.api.generation.dto

import com.plot.api.generation.GenerationRunStatus
import com.plot.api.generation.GenerationWorkflowState
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.SentenceOrigin
import com.plot.api.generation.model.SourceProvider
import com.plot.api.contentpack.dto.ContentPackResponse
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateGenerationRequest(
	@field:NotNull val sourceScopeId: UUID?,
	@field:NotEmpty @field:Size(max = 20) val writingBlockIds: List<UUID>,
	@field:Size(max = 2_000) val instruction: String? = null,
)

data class GenerationRunResponse(
	val id: UUID,
	val status: String,
	val semanticRewriteAttempt: Int,
	val pollAfterMs: Long?,
	val failureCode: String?,
	val evidence: List<GenerationEvidenceResponse>,
	val sentences: List<GenerationSentenceResponse>,
	val artifacts: List<GenerationArtifactResponse>,
	val pendingIntervention: GenerationInterventionResponse?,
	val timing: GenerationRunTimingResponse? = null,
	val contentPack: ContentPackResponse? = null,
)

data class GenerationRunTimingResponse(
	val createdAt: Instant,
	val startedAt: Instant?,
	val finishedAt: Instant?,
	val steps: List<GenerationStepTimingResponse>,
	val model: GenerationModelTimingResponse?,
)

data class GenerationStepTimingResponse(
	val kind: String,
	val sequence: Int,
	val status: String,
	val startedAt: Instant,
	val finishedAt: Instant?,
	val durationMs: Long?,
	val failureCode: String?,
)

data class GenerationModelTimingResponse(
	val modelName: String,
	val totalTokens: Long,
	val totalLatencyMs: Long,
)

data class GenerationEvidenceResponse(
	val id: UUID,
	val provider: SourceProvider,
	val sourceKind: String,
	val sourceLabel: String,
	val originalUrl: String,
	val snapshotExcerpt: String?,
	val contentHash: String,
)

data class GenerationSentenceResponse(
	val id: UUID,
	val revisionId: UUID,
	val revisionNumber: Int,
	val orderIndex: Int,
	val body: String,
	val origin: SentenceOrigin,
	val verdict: String?,
	val reason: String?,
	val citations: List<GenerationCitationResponse>,
)

data class GenerationArtifactResponse(
	val kind: String,
	val sequence: Int,
	val sentenceIds: List<UUID>,
	val reviews: List<GenerationArtifactReviewResponse>,
	val detail: String?,
)

data class GenerationArtifactReviewResponse(
	val sentenceId: UUID,
	val verdict: ReviewVerdict,
	val evidenceIds: List<UUID>,
	val reason: String?,
)

data class GenerationCitationResponse(
	val evidenceId: UUID,
	val provider: SourceProvider,
	val sourceLabel: String,
	val originalUrl: String,
	val snapshotExcerpt: String?,
)

data class GenerationInterventionResponse(
	val id: UUID,
	val sentenceId: UUID,
	val version: Long,
	val reason: String,
	val evidenceIds: List<UUID>,
)

fun GenerationWorkflowState.toResponse(): GenerationRunResponse {
	val reviewsBySentence = reviews.associateBy { it.sentenceId }
	val evidenceById = evidence.associateBy { it.id }
	val reviewedRevisionIds = artifacts
		.filter { it.kind.name == "REVIEWER_OUTPUT" }
		.flatMap { it.sentences }
		.map { it.revisionId }
		.toSet()
	return GenerationRunResponse(
		id = runId,
		status = status.name,
		semanticRewriteAttempt = semanticRewriteAttempt,
		pollAfterMs = if (status !in GenerationRunStatus.terminalOrPaused) 500 else null,
		failureCode = failureCode,
		evidence = evidence.map {
			GenerationEvidenceResponse(it.id, it.sourceProvider, it.sourceKind, it.sourceLabel, it.originalUrl, it.snapshotExcerpt, it.contentHash)
		},
		sentences = sentences.sortedBy { it.orderIndex }.map { sentence ->
			val review = reviewsBySentence[sentence.id]
			val userModified = sentence.origin == SentenceOrigin.USER_MODIFIED
			val reviewFailed = failureCode != null && sentence.revisionId !in reviewedRevisionIds
			GenerationSentenceResponse(
				sentence.id, sentence.revisionId, sentence.revisionNumber, sentence.orderIndex, sentence.body,
				sentence.origin,
				when {
					userModified -> SentenceOrigin.USER_MODIFIED.name
					reviewFailed -> "REVIEW_FAILED"
					else -> review?.verdict?.name
				},
				when {
					userModified -> null
					reviewFailed -> failureCode
					else -> review?.reason
				},
				(if (userModified || reviewFailed) emptyList() else review?.evidenceIds.orEmpty()).mapNotNull { id -> evidenceById[id] }.map {
					GenerationCitationResponse(it.id, it.sourceProvider, it.sourceLabel, it.originalUrl, it.snapshotExcerpt)
				},
			)
		},
		artifacts = artifacts.sortedBy { it.sequence }.map { artifact ->
			GenerationArtifactResponse(
				artifact.kind.name,
				artifact.sequence,
				artifact.sentences.map { it.id },
				artifact.reviews.map { GenerationArtifactReviewResponse(it.sentenceId, it.verdict, it.evidenceIds, it.reason) },
				artifact.detail,
			)
		},
		pendingIntervention = pendingIntervention?.let {
			GenerationInterventionResponse(it.id, it.sentenceId, it.version, it.reason, it.evidenceIds)
		},
		contentPack = null,
	)
}
