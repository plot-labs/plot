package com.plot.api.artifact.workflow

import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ArtifactLayoutNode
import com.plot.api.artifact.workflow.model.ReviewVerdict
import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.SentenceArtifact
import com.plot.api.artifact.workflow.model.SentenceIntent
import com.plot.api.artifact.workflow.model.SentenceOrigin
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import com.plot.api.artifact.workflow.model.ValidatedSentenceReview
import com.plot.api.artifact.workflow.model.WriterOutput
import com.plot.api.artifact.workflow.model.WriterLayoutNode
import java.util.UUID

class InvalidModelOutputException(message: String) : IllegalArgumentException(message)

class ModelOutputValidator {
	fun assignSentenceIds(
		runId: UUID,
		output: WriterOutput,
		availableEvidenceIds: Set<UUID>,
		maxSentences: Int? = null,
		idGenerator: () -> UUID,
	): List<SentenceArtifact> {
		if (output.sentences.isEmpty()) invalid("Writer output must contain at least one sentence")
		if (maxSentences != null && output.sentences.size > maxSentences) {
			invalid("Writer output must contain at most $maxSentences sentences")
		}
		if (output.sentences.count { it.intent == SentenceIntent.UNRESOLVED_CONFLICT } > 1) {
			invalid("Writer output must represent a material disagreement as exactly one unresolved conflict sentence")
		}
		return output.sentences.mapIndexed { index, sentence ->
			val body = validateProseOnlyBody(sentence.body, "Writer sentence at index $index")
			validateConflictDeclaration(sentence.intent, sentence.conflictEvidenceIds, availableEvidenceIds, index)
			SentenceArtifact(
				id = idGenerator(),
				artifactWorkflowRunId = runId,
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

	/**
	 * Converts writer-local sentence indexes into server-owned statement IDs.
	 * An empty layout is deliberately accepted for old writer schemas; the
	 * materializer then emits one V2 paragraph per sentence.
	 */
	fun assignLayout(
		layout: List<WriterLayoutNode>,
		writerSentences: List<com.plot.api.artifact.workflow.model.WriterSentence>,
		sentences: List<SentenceArtifact>,
	): List<ArtifactLayoutNode> {
		if (layout.isEmpty()) return emptyList()
		if (writerSentences.size != sentences.size) invalid("Writer layout sentence mapping is out of date")
		val consumed = linkedSetOf<Int>()
		val normalized = layout.mapIndexed { index, node ->
			normalizeLayoutNode(node, "Writer layout block $index", sentences, consumed, inList = false)
		}
		if (consumed != sentences.indices.toSet()) {
			invalid("Writer layout must reference every sentence exactly once")
		}
		return normalized
	}

	/** Removes layout leaves for conflicts/omissions without leaving empty lists. */
	fun retainLayout(layout: List<ArtifactLayoutNode>, statementIds: Set<UUID>): List<ArtifactLayoutNode> =
		layout.mapNotNull { retainLayoutNode(it, statementIds) }

	private fun normalizeLayoutNode(
		node: WriterLayoutNode,
		path: String,
		sentences: List<SentenceArtifact>,
		consumed: MutableSet<Int>,
		inList: Boolean,
	): ArtifactLayoutNode {
		val type = node.type.trim()
		return when (type) {
			"heading" -> {
				if (inList) invalid("$path cannot contain a heading")
				val tag = node.tag?.trim()?.takeIf { it in HEADING_TAGS }
					?: invalid("$path heading tag must be h1, h2, or h3")
				leafLayout(node, path, type, tag, sentences, consumed)
			}
			"paragraph" -> {
				if (inList) invalid("$path cannot contain a paragraph")
				leafLayout(node, path, type, null, sentences, consumed)
			}
			"list" -> {
				if (inList) invalid("$path cannot contain a nested list")
				val listType = node.listType?.trim()?.takeIf { it in LIST_TYPES }
					?: invalid("$path list type must be bullet or ordered")
				val start = node.start ?: 1
				if (start < 1) invalid("$path list start must be positive")
				if (node.children.isEmpty()) invalid("$path must contain at least one list item")
				val children = node.children.mapIndexed { index, child ->
					if (child.type.trim() != "listItem") invalid("$path item $index must be a list item")
					normalizeLayoutNode(child, "$path item $index", sentences, consumed, inList = true)
				}
				ArtifactLayoutNode(type, listType = listType, start = start, children = children)
			}
			"listItem" -> {
				if (!inList) invalid("$path list items must be inside a list")
				if (node.children.isNotEmpty()) invalid("$path must not contain child layout nodes")
				leafLayout(node, path, type, null, sentences, consumed)
			}
			else -> invalid("$path has unsupported type '$type'")
		}
	}

	private fun leafLayout(
		node: WriterLayoutNode,
		path: String,
		type: String,
		tag: String?,
		sentences: List<SentenceArtifact>,
		consumed: MutableSet<Int>,
	): ArtifactLayoutNode {
		if (node.children.isNotEmpty()) invalid("$path must not contain child layout nodes")
		val index = node.statementIndex ?: invalid("$path statementIndex is required")
		if (index !in sentences.indices) invalid("$path statementIndex is unknown")
		if (!consumed.add(index)) invalid("$path references a sentence more than once")
		return ArtifactLayoutNode(type = type, statementId = sentences[index].id, tag = tag)
	}

	private fun retainLayoutNode(node: ArtifactLayoutNode, statementIds: Set<UUID>): ArtifactLayoutNode? {
		if (node.type == "list") {
			val children = node.children.mapNotNull { retainLayoutNode(it, statementIds) }
			return if (children.isEmpty()) null else node.copy(children = children)
		}
		return if (node.statementId == null || node.statementId in statementIds) node else null
	}

	fun validateReview(
		runId: UUID,
		sentences: List<SentenceArtifact>,
		evidence: List<EvidenceSnapshot>,
		output: ReviewerOutput,
	): List<ValidatedSentenceReview> {
		if (sentences.any { it.artifactWorkflowRunId != runId }) invalid("Sentence belongs to another run")
		if (evidence.any { it.artifactWorkflowRunId != runId }) invalid("Evidence belongs to another run")
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
		if (current.any { it.artifactWorkflowRunId != runId }) invalid("Sentence belongs to another run")
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
		val HEADING_TAGS = setOf("h1", "h2", "h3")
		val LIST_TYPES = setOf("bullet", "ordered")
		val providerAuthoredCitationPattern = Regex(
			"""(?i)\bhttps?://|\[[^\]]*]\s*\([^)]*\)|\((?:PR|GitHub|Slack|Linear|source)\s*:""",
		)
	}
}
