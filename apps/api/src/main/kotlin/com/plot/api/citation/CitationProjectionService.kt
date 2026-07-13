package com.plot.api.citation

import com.plot.api.generation.InvalidModelOutputException
import com.plot.api.generation.model.CitationProjection
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.SentenceArtifact
import com.plot.api.generation.model.SentenceCitationProjection
import com.plot.api.generation.model.ValidatedSentenceReview

class CitationProjectionService {
	fun project(
		sentence: SentenceArtifact,
		review: ValidatedSentenceReview,
		evidence: List<EvidenceSnapshot>,
	): SentenceCitationProjection {
		if (review.sentenceId != sentence.id) throw InvalidModelOutputException("Review targets another sentence")
		if (evidence.any { it.generationRunId != sentence.generationRunId }) {
			throw InvalidModelOutputException("Evidence belongs to another run")
		}
		if (review.verdict != ReviewVerdict.SUPPORTED) {
			return SentenceCitationProjection(sentence.id, emptyList())
		}
		val evidenceById = evidence.associateBy { it.id }
		return SentenceCitationProjection(
			sentenceId = sentence.id,
			citations = review.evidenceIds.map { evidenceId ->
				val snapshot = evidenceById[evidenceId]
					?: throw InvalidModelOutputException("Unknown evidence reference")
				CitationProjection(
					evidenceId = snapshot.id,
					provider = snapshot.sourceProvider,
					sourceLabel = snapshot.sourceLabel,
					originalUrl = snapshot.originalUrl,
					snapshotExcerpt = snapshot.snapshotExcerpt,
				)
			},
		)
	}
}
