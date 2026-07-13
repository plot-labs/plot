package com.plot.api.generation.model

import java.time.Instant
import java.util.UUID

enum class SourceProvider { GITHUB, SLACK, LINEAR }

data class EvidenceSnapshot(
	val id: UUID,
	val generationRunId: UUID,
	val writingBlockId: UUID,
	val orderIndex: Int,
	val sourceProvider: SourceProvider,
	val sourceKind: String,
	val sourceLabel: String,
	val snapshotTitle: String?,
	val snapshotBody: String,
	val snapshotExcerpt: String?,
	val originalUrl: String,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
	val contentHash: String,
	val capturedAt: Instant,
)

data class WriterOutput(val sentences: List<WriterSentence>)

data class WriterSentence(val body: String)

enum class SentenceOrigin { GENERATED, REWRITTEN, USER_MODIFIED }

data class SentenceArtifact(
	val id: UUID,
	val generationRunId: UUID,
	val revisionId: UUID,
	val revisionNumber: Int,
	val orderIndex: Int,
	val body: String,
	val origin: SentenceOrigin,
)

enum class ReviewVerdict { SUPPORTED, NOT_REQUIRED, NEEDS_SUPPORT, CONFLICT }

data class ReviewerOutput(val reviews: List<SentenceReview>)

data class SentenceReview(
	val sentenceId: UUID,
	val verdict: ReviewVerdict,
	val evidenceIds: List<UUID> = emptyList(),
	val reason: String? = null,
	/** Untrusted provider output retained only so validation can explicitly discard it. */
	val modelSuppliedUrls: List<String> = emptyList(),
)

data class ValidatedSentenceReview(
	val sentenceId: UUID,
	val verdict: ReviewVerdict,
	val evidenceIds: List<UUID>,
	val reason: String?,
)

data class TargetedRewriteOutput(val rewrites: List<TargetedRewrite>)

data class TargetedRewrite(val sentenceId: UUID, val body: String)

enum class CitationStatus { ACTIVE, STALE, REMOVED }

data class SentenceCitation(
	val sentenceId: UUID,
	val sentenceRevisionId: UUID,
	val evidenceId: UUID,
	val orderIndex: Int,
	val status: CitationStatus = CitationStatus.ACTIVE,
)

data class CitationProjection(
	val evidenceId: UUID,
	val provider: SourceProvider,
	val sourceLabel: String,
	val originalUrl: String,
	val snapshotExcerpt: String?,
)

data class SentenceCitationProjection(
	val sentenceId: UUID,
	val citations: List<CitationProjection>,
)

enum class ExportSentenceStatus { SUPPORTED, NOT_REQUIRED, NEEDS_SUPPORT, CONFLICT, USER_MODIFIED }

data class ExportSentence(
	val id: UUID,
	val revisionId: UUID,
	val orderIndex: Int,
	val body: String,
	val status: ExportSentenceStatus,
	val citations: List<SentenceCitation> = emptyList(),
)

data class MarkdownExport(
	val markdown: String,
	val unresolvedCount: Int,
	val warningAcknowledged: Boolean,
)
