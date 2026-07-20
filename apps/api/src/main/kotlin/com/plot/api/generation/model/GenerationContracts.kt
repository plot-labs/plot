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

enum class SentenceIntent { FACTUAL, EDITORIAL, UNRESOLVED_CONFLICT }

data class WriterSentence(
	val body: String,
	val intent: SentenceIntent = SentenceIntent.FACTUAL,
	val conflictEvidenceIds: List<UUID> = emptyList(),
)

enum class SentenceOrigin { GENERATED, REWRITTEN, USER_MODIFIED }

data class SentenceArtifact(
	val id: UUID,
	val generationRunId: UUID,
	val revisionId: UUID,
	val revisionNumber: Int,
	val orderIndex: Int,
	val body: String,
	val origin: SentenceOrigin,
	val intent: SentenceIntent = SentenceIntent.FACTUAL,
	val conflictEvidenceIds: List<UUID> = emptyList(),
)

enum class ReviewVerdict { SUPPORTED, NOT_REQUIRED, NEEDS_SUPPORT, CONFLICT }

data class ReviewerOutput(
	val reviews: List<SentenceReview>,
	val documentConflicts: List<DocumentConflict> = emptyList(),
)

data class DocumentConflict(
	val sentenceIds: List<UUID>,
	val evidenceIds: List<UUID>,
	val reason: String,
)

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

data class TargetedRewrite(
	val sentenceId: UUID,
	val body: String? = null,
	val omit: Boolean = false,
)

enum class CitationStatus { ACTIVE, STALE, REMOVED }

data class SentenceCitation(
	val sentenceId: UUID,
	val sentenceRevisionId: UUID,
	val evidenceId: UUID,
	val orderIndex: Int,
	val status: CitationStatus = CitationStatus.ACTIVE,
)

enum class ExportSentenceStatus { SUPPORTED, NOT_REQUIRED, NEEDS_SUPPORT, CONFLICT, USER_MODIFIED, REVIEW_FAILED }

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
