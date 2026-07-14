package com.plot.api.generation

import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.SentenceArtifact
import com.plot.api.generation.model.SentenceOrigin
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.ValidatedSentenceReview
import com.plot.api.generation.model.WriterOutput
import java.util.UUID

class InvalidModelOutputException(message: String) : IllegalArgumentException(message)

class ModelOutputValidator {
	fun assignSentenceIds(
		runId: UUID,
		output: WriterOutput,
		idGenerator: () -> UUID,
	): List<SentenceArtifact> {
		if (output.sentences.isEmpty()) invalid("Writer output must contain at least one sentence")
		return output.sentences.mapIndexed { index, sentence ->
			val body = sentence.body.trim()
			if (body.isBlank()) invalid("Writer sentence at index $index is blank")
			SentenceArtifact(
				id = idGenerator(),
				generationRunId = runId,
				revisionId = idGenerator(),
				revisionNumber = 1,
				orderIndex = index,
				body = body,
				origin = SentenceOrigin.GENERATED,
			)
		}
	}

	fun validateReview(
		runId: UUID,
		sentences: List<SentenceArtifact>,
		evidence: List<EvidenceSnapshot>,
		output: ReviewerOutput,
	): List<ValidatedSentenceReview> {
		if (sentences.any { it.generationRunId != runId }) invalid("Sentence belongs to another run")
		if (evidence.any { it.generationRunId != runId }) invalid("Evidence belongs to another run")
		val expectedSentenceIds = sentences.map { it.id }
		val actualSentenceIds = output.reviews.map { it.sentenceId }
		if (actualSentenceIds.toSet().size != actualSentenceIds.size) invalid("Duplicate sentence review")
		if (actualSentenceIds.toSet() != expectedSentenceIds.toSet() || actualSentenceIds.size != expectedSentenceIds.size) {
			invalid("Reviewer output must cover every sentence exactly once")
		}
		val evidenceById = evidence.associateBy { it.id }
		return output.reviews.associateBy { it.sentenceId }.let { bySentence ->
			expectedSentenceIds.map { sentenceId ->
				val review = bySentence.getValue(sentenceId)
				if (review.evidenceIds.distinct().size != review.evidenceIds.size) invalid("Duplicate evidence reference")
				if (review.evidenceIds.any { it !in evidenceById }) invalid("Unknown evidence reference")
				when (review.verdict) {
					ReviewVerdict.SUPPORTED -> if (review.evidenceIds.isEmpty()) invalid("SUPPORTED requires evidence")
					ReviewVerdict.NOT_REQUIRED -> if (review.evidenceIds.isNotEmpty()) invalid("NOT_REQUIRED cannot cite evidence")
					ReviewVerdict.NEEDS_SUPPORT,
					ReviewVerdict.CONFLICT,
					-> if (review.reason.isNullOrBlank()) invalid("${review.verdict} requires a reason")
				}
				ValidatedSentenceReview(
					sentenceId = sentenceId,
					verdict = review.verdict,
					evidenceIds = review.evidenceIds.toList(),
					reason = review.reason?.trim(),
				)
			}
		}
	}

	fun applyTargetedRewrite(
		runId: UUID,
		current: List<SentenceArtifact>,
		targetSentenceIds: List<UUID>,
		output: TargetedRewriteOutput,
		revisionIdGenerator: () -> UUID,
	): List<SentenceArtifact> {
		if (current.any { it.generationRunId != runId }) invalid("Sentence belongs to another run")
		if (targetSentenceIds.isEmpty() || targetSentenceIds.distinct().size != targetSentenceIds.size) {
			invalid("Rewrite targets must be unique and non-empty")
		}
		if (targetSentenceIds.any { target -> current.none { it.id == target } }) invalid("Unknown rewrite target")
		if (output.rewrites.map { it.sentenceId } != targetSentenceIds) {
			invalid("Rewriter must return exactly the requested sentence IDs in order")
		}
		val rewrites = output.rewrites.associateBy { it.sentenceId }
		return current.map { sentence ->
			val rewrite = rewrites[sentence.id] ?: return@map sentence
			val body = rewrite.body.trim()
			if (body.isBlank()) invalid("Rewritten sentence cannot be blank")
			sentence.copy(
				revisionId = revisionIdGenerator(),
				revisionNumber = sentence.revisionNumber + 1,
				body = body,
				origin = SentenceOrigin.REWRITTEN,
			)
		}
	}

	private fun invalid(message: String): Nothing = throw InvalidModelOutputException(message)
}
