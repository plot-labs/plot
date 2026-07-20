package com.plot.api.generation

import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.SentenceArtifact
import com.plot.api.generation.model.SentenceIntent
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
		availableEvidenceIds: Set<UUID>,
		idGenerator: () -> UUID,
	): List<SentenceArtifact> {
		if (output.sentences.isEmpty()) invalid("Writer output must contain at least one sentence")
		if (output.sentences.count { it.intent == SentenceIntent.UNRESOLVED_CONFLICT } > 1) {
			invalid("Writer output must represent a material disagreement as exactly one unresolved conflict sentence")
		}
		return output.sentences.mapIndexed { index, sentence ->
			val body = validateProseOnlyBody(sentence.body, "Writer sentence at index $index")
			validateConflictDeclaration(sentence.intent, sentence.conflictEvidenceIds, availableEvidenceIds, index)
			SentenceArtifact(
				id = idGenerator(),
				generationRunId = runId,
				revisionId = idGenerator(),
				revisionNumber = 1,
				orderIndex = index,
				body = body,
				origin = SentenceOrigin.GENERATED,
				intent = sentence.intent,
				conflictEvidenceIds = sentence.conflictEvidenceIds.toList(),
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
		val sentencesById = sentences.associateBy { it.id }
		val validatedReviews = output.reviews.associateBy { it.sentenceId }.let { bySentence ->
			expectedSentenceIds.map { sentenceId ->
				val review = bySentence.getValue(sentenceId)
				val sentence = sentencesById.getValue(sentenceId)
				if (review.evidenceIds.distinct().size != review.evidenceIds.size) invalid("Duplicate evidence reference")
				if (review.evidenceIds.any { it !in evidenceById }) invalid("Unknown evidence reference")
				if (sentence.intent == SentenceIntent.UNRESOLVED_CONFLICT) {
					return@map ValidatedSentenceReview(
						sentenceId = sentenceId,
						verdict = ReviewVerdict.CONFLICT,
						evidenceIds = sentence.conflictEvidenceIds,
						reason = review.reason?.trim().takeUnless { it.isNullOrBlank() }
							?: "Materially incompatible evidence requires a user decision.",
					)
				}
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
		if (output.documentConflicts.isEmpty()) return validatedReviews

		val validatedBySentence = validatedReviews.associateBy { it.sentenceId }.toMutableMap()
		output.documentConflicts.forEach { conflict ->
			if (conflict.sentenceIds.size < 2) invalid("Document conflict requires at least two sentence IDs")
			if (conflict.sentenceIds.distinct().size != conflict.sentenceIds.size) {
				invalid("Document conflict has duplicate sentence IDs")
			}
			if (conflict.sentenceIds.any { it !in sentencesById }) invalid("Document conflict has an unknown sentence ID")
			if (conflict.evidenceIds.size < 2) invalid("Document conflict requires at least two evidence IDs")
			if (conflict.evidenceIds.distinct().size != conflict.evidenceIds.size) {
				invalid("Document conflict has duplicate evidence IDs")
			}
			if (conflict.evidenceIds.any { it !in evidenceById }) invalid("Document conflict has an unknown evidence ID")
			val reason = conflict.reason.trim()
			if (reason.isBlank()) invalid("Document conflict requires a reason")
			val involvedReviews = conflict.sentenceIds.map(validatedBySentence::getValue)
			if (conflict.evidenceIds.any { evidenceId -> involvedReviews.none { evidenceId in it.evidenceIds } }) {
				invalid("Document conflict evidence must support one of its sentences")
			}
			conflict.sentenceIds.forEach { sentenceId ->
				validatedBySentence[sentenceId] = ValidatedSentenceReview(
					sentenceId = sentenceId,
					verdict = ReviewVerdict.CONFLICT,
					evidenceIds = conflict.evidenceIds.toList(),
					reason = reason,
				)
			}
		}
		return expectedSentenceIds.map(validatedBySentence::getValue)
	}

	private fun validateConflictDeclaration(
		intent: SentenceIntent,
		conflictEvidenceIds: List<UUID>,
		availableEvidenceIds: Set<UUID>,
		index: Int,
	) {
		if (intent != SentenceIntent.UNRESOLVED_CONFLICT) {
			if (conflictEvidenceIds.isNotEmpty()) invalid("Only UNRESOLVED_CONFLICT may declare conflict evidence")
			return
		}
		if (conflictEvidenceIds.size < 2) {
			invalid("UNRESOLVED_CONFLICT at index $index requires at least two evidence IDs")
		}
		if (conflictEvidenceIds.distinct().size != conflictEvidenceIds.size) {
			invalid("UNRESOLVED_CONFLICT at index $index has duplicate evidence IDs")
		}
		if (conflictEvidenceIds.any { it !in availableEvidenceIds }) {
			invalid("UNRESOLVED_CONFLICT at index $index has an unknown evidence ID")
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
		val revised = current.mapNotNull { sentence ->
			val rewrite = rewrites[sentence.id] ?: return@mapNotNull sentence
			if (rewrite.omit) {
				if (!rewrite.body.isNullOrBlank()) invalid("Omitted sentence cannot include a body")
				return@mapNotNull null
			}
			val rewriteBody = rewrite.body ?: invalid("Rewritten sentence body is required")
			val body = validateProseOnlyBody(rewriteBody, "Rewritten sentence")
			sentence.copy(
				revisionId = revisionIdGenerator(),
				revisionNumber = sentence.revisionNumber + 1,
				body = body,
				origin = SentenceOrigin.REWRITTEN,
			)
		}
		if (revised.isEmpty()) invalid("Rewrite cannot omit every sentence")
		return revised
	}

	private fun validateProseOnlyBody(value: String, label: String): String {
		val body = value.trim()
		if (body.isBlank()) invalid("$label is blank")
		if (providerAuthoredCitationPattern.containsMatchIn(body)) {
			invalid("$label must not contain provider-authored citation markup")
		}
		return body
	}

	private fun invalid(message: String): Nothing = throw InvalidModelOutputException(message)

	private companion object {
		val providerAuthoredCitationPattern = Regex(
			"""(?i)\bhttps?://|\[[^\]]*]\s*\([^)]*\)|\((?:PR|GitHub|Slack|Linear|source)\s*:""",
		)
	}
}
