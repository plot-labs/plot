package com.plot.api.artifact.workflow.dto

import com.plot.api.artifact.workflow.ArtifactWorkflowRunStatus
import com.plot.api.artifact.workflow.ArtifactWorkflowState
import com.plot.api.artifact.workflow.model.ReviewVerdict
import com.plot.api.artifact.workflow.model.SentenceOrigin
import com.plot.api.artifact.workflow.model.SourceProvider
import com.plot.api.artifact.dto.ArtifactResponse
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateArtifactWorkflowRequest(
	@field:NotNull val sourceScopeId: UUID?,
	@field:NotEmpty @field:Size(max = 20) val writingBlockIds: List<UUID>,
	@field:Size(max = 2_000) val instruction: String? = null,
	val workSessionId: UUID? = null,
)

data class ArtifactWorkflowRunResponse(
	val id: UUID,
	val status: String,
	val semanticRewriteAttempt: Int,
	val pollAfterMs: Long?,
	val failureCode: String?,
	val evidence: List<ArtifactWorkflowEvidenceResponse>,
	val sentences: List<ArtifactWorkflowSentenceResponse>,
	val artifacts: List<ArtifactWorkflowArtifactResponse>,
	val timing: ArtifactWorkflowRunTimingResponse? = null,
	val artifact: ArtifactResponse? = null,
	val workSessionId: UUID? = null,
)

data class ArtifactWorkflowRunTimingResponse(
	val createdAt: Instant,
	val startedAt: Instant?,
	val finishedAt: Instant?,
	val steps: List<ArtifactWorkflowStepTimingResponse>,
	val model: ArtifactWorkflowModelTimingResponse?,
)

data class ArtifactWorkflowStepTimingResponse(
	val kind: String,
	val sequence: Int,
	val status: String,
	val startedAt: Instant,
	val finishedAt: Instant?,
	val durationMs: Long?,
	val failureCode: String?,
)

data class ArtifactWorkflowModelTimingResponse(
	val modelName: String,
	val totalTokens: Long,
	val totalLatencyMs: Long,
)

data class ArtifactWorkflowEvidenceResponse(
	val id: UUID,
	val provider: SourceProvider,
	val sourceKind: String,
	val sourceLabel: String,
	val originalUrl: String,
	val snapshotExcerpt: String?,
	val contentHash: String,
)

data class ArtifactWorkflowSentenceResponse(
	val id: UUID,
	val revisionId: UUID,
	val revisionNumber: Int,
	val orderIndex: Int,
	val body: String,
	val origin: SentenceOrigin,
	val verdict: String?,
	val reason: String?,
	val citations: List<ArtifactWorkflowCitationResponse>,
)

data class ArtifactWorkflowArtifactResponse(
	val kind: String,
	val sequence: Int,
	val sentenceIds: List<UUID>,
	val reviews: List<ArtifactWorkflowArtifactReviewResponse>,
	val detail: String?,
)

data class ArtifactWorkflowArtifactReviewResponse(
	val sentenceId: UUID,
	val verdict: ReviewVerdict,
	val evidenceIds: List<UUID>,
	val reason: String?,
)

data class ArtifactWorkflowCitationResponse(
	val evidenceId: UUID,
	val provider: SourceProvider,
	val sourceLabel: String,
	val originalUrl: String,
	val snapshotExcerpt: String?,
)

fun ArtifactWorkflowState.toResponse(): ArtifactWorkflowRunResponse {
	val reviewsBySentence = reviews.associateBy { it.sentenceId }
	val evidenceById = evidence.associateBy { it.id }
	val reviewedRevisionIds = artifacts
		.filter { it.kind.name == "REVIEWER_OUTPUT" }
		.flatMap { it.sentences }
		.map { it.revisionId }
		.toSet()
	return ArtifactWorkflowRunResponse(
		id = runId,
		status = status.name,
		semanticRewriteAttempt = semanticRewriteAttempt,
		pollAfterMs = if (status !in ArtifactWorkflowRunStatus.terminalOrPaused) 500 else null,
		failureCode = failureCode,
		evidence = evidence.map {
			ArtifactWorkflowEvidenceResponse(it.id, it.sourceProvider, it.sourceKind, it.sourceLabel, it.originalUrl, it.snapshotExcerpt, it.contentHash)
		},
		sentences = sentences.sortedBy { it.orderIndex }.map { sentence ->
			val review = reviewsBySentence[sentence.id]
			val userModified = sentence.origin == SentenceOrigin.USER_MODIFIED
			val reviewFailed = failureCode != null && sentence.revisionId !in reviewedRevisionIds
			ArtifactWorkflowSentenceResponse(
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
					ArtifactWorkflowCitationResponse(it.id, it.sourceProvider, it.sourceLabel, it.originalUrl, it.snapshotExcerpt)
				},
			)
		},
		artifacts = artifacts.sortedBy { it.sequence }.map { artifact ->
			ArtifactWorkflowArtifactResponse(
				artifact.kind.name,
				artifact.sequence,
				artifact.sentences.map { it.id },
				artifact.reviews.map { ArtifactWorkflowArtifactReviewResponse(it.sentenceId, it.verdict, it.evidenceIds, it.reason) },
				artifact.detail,
			)
		},
		artifact = null,
		workSessionId = workSessionId,
	)
}
