package com.plot.api.generation.dto

import com.plot.api.generation.ConflictResolutionAction
import com.plot.api.generation.GenerationWorkflowState
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.SentenceOrigin
import com.plot.api.generation.model.SourceProvider
import com.plot.api.contentpack.dto.ContentPackResponse
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateGenerationRequest(
	@field:NotNull val sourceScopeId: UUID?,
	@field:NotEmpty val writingBlockIds: List<UUID>,
	@field:Size(max = 2_000) val instruction: String? = null,
)

data class ResolveConflictRequest(
	@field:NotNull val expectedVersion: Long?,
	@field:NotNull val action: ConflictResolutionAction?,
	val preferredEvidenceId: UUID? = null,
	@field:Size(max = 4_000) val providedWording: String? = null,
)

data class GenerationRunResponse(
	val id: UUID,
	val status: String,
	val semanticRewriteAttempt: Int,
	val pollAfterMs: Long?,
	val failureCode: String?,
	val evidence: List<GenerationEvidenceResponse>,
	val sentences: List<GenerationSentenceResponse>,
	val pendingIntervention: GenerationInterventionResponse?,
	val contentPack: ContentPackResponse? = null,
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
	val verdict: ReviewVerdict?,
	val reason: String?,
	val citations: List<GenerationCitationResponse>,
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
	return GenerationRunResponse(
		id = runId,
		status = status.name,
		semanticRewriteAttempt = semanticRewriteAttempt,
		pollAfterMs = if (status.name in setOf("QUEUED", "WRITING", "REVIEWING", "REWRITING")) 500 else null,
		failureCode = failureCode,
		evidence = evidence.map {
			GenerationEvidenceResponse(it.id, it.sourceProvider, it.sourceKind, it.sourceLabel, it.originalUrl, it.snapshotExcerpt, it.contentHash)
		},
		sentences = sentences.sortedBy { it.orderIndex }.map { sentence ->
			val review = reviewsBySentence[sentence.id]
			GenerationSentenceResponse(
				sentence.id, sentence.revisionId, sentence.revisionNumber, sentence.orderIndex, sentence.body,
				sentence.origin, review?.verdict, review?.reason,
				review?.evidenceIds.orEmpty().mapNotNull { id -> evidenceById[id] }.map {
					GenerationCitationResponse(it.id, it.sourceProvider, it.sourceLabel, it.originalUrl, it.snapshotExcerpt)
				},
			)
		},
		pendingIntervention = pendingIntervention?.let {
			GenerationInterventionResponse(it.id, it.sentenceId, it.version, it.reason, it.evidenceIds)
		},
		contentPack = null,
	)
}
