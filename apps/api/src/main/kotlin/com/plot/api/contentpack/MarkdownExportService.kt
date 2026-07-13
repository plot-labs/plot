package com.plot.api.contentpack

import com.plot.api.generation.model.CitationStatus
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ExportSentence
import com.plot.api.generation.model.ExportSentenceStatus
import com.plot.api.generation.model.MarkdownExport
import java.util.UUID

class UnresolvedExportException(val unresolvedCount: Int) :
	IllegalStateException("Export requires explicit acknowledgement for $unresolvedCount unresolved sentences")

class MarkdownExportService {
	fun render(
		sentences: List<ExportSentence>,
		evidence: List<EvidenceSnapshot>,
		acknowledgeUnresolved: Boolean,
	): MarkdownExport {
		val orderedSentences = sentences.sortedBy { it.orderIndex }
		if (orderedSentences.map { it.orderIndex }.distinct().size != orderedSentences.size) {
			throw IllegalArgumentException("Sentence order must be unique")
		}
		if (orderedSentences.any { it.body.isBlank() }) throw IllegalArgumentException("Export sentence is blank")
		val unresolvedCount = orderedSentences.count { it.status.isUnresolved }
		if (unresolvedCount > 0 && !acknowledgeUnresolved) throw UnresolvedExportException(unresolvedCount)

		val evidenceById = evidence.associateBy { it.id }
		val sourceNumbers = linkedMapOf<UUID, Int>()
		val renderedSentences = orderedSentences.map { sentence ->
			val references = if (sentence.status == ExportSentenceStatus.SUPPORTED) {
				sentence.citations
					.asSequence()
					.filter { it.status == CitationStatus.ACTIVE }
					.filter { it.sentenceId == sentence.id && it.sentenceRevisionId == sentence.revisionId }
					.sortedBy { it.orderIndex }
					.map { citation ->
						val snapshot = evidenceById[citation.evidenceId]
							?: throw IllegalArgumentException("Citation references unknown evidence")
						sourceNumbers.getOrPut(snapshot.id) { sourceNumbers.size + 1 }
					}
					.distinct()
					.toList()
			} else {
				emptyList()
			}
			buildString {
				append(sentence.body.trim())
				if (references.isNotEmpty()) {
					append(' ')
					append(references.joinToString(separator = "") { "[$it]" })
				}
			}
		}

		val markdown = buildString {
			append(renderedSentences.joinToString("\n\n"))
			if (sourceNumbers.isNotEmpty()) {
				append("\n\n## Sources\n\n")
				sourceNumbers.forEach { (evidenceId, number) ->
					val source = evidenceById.getValue(evidenceId)
					append(number)
					append(". [")
					append(escapeLabel(source.sourceLabel))
					append("](")
					append(source.originalUrl)
					append(")\n")
				}
			} else if (isNotEmpty()) {
				append('\n')
			}
		}

		return MarkdownExport(
			markdown = markdown,
			unresolvedCount = unresolvedCount,
			warningAcknowledged = acknowledgeUnresolved && unresolvedCount > 0,
		)
	}

	private fun escapeLabel(label: String): String = label.replace("\\", "\\\\").replace("]", "\\]")
}

private val ExportSentenceStatus.isUnresolved: Boolean
	get() = this in setOf(
		ExportSentenceStatus.NEEDS_SUPPORT,
		ExportSentenceStatus.CONFLICT,
		ExportSentenceStatus.USER_MODIFIED,
	)
