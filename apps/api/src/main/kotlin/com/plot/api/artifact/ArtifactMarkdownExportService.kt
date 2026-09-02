package com.plot.api.artifact

import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ExportSentence
import com.plot.api.artifact.workflow.model.ExportSentenceStatus
import com.plot.api.artifact.workflow.model.ExportSource
import com.plot.api.artifact.workflow.model.MarkdownExport
import java.net.URI
import org.springframework.stereotype.Service

class UnresolvedExportException(val unresolvedCount: Int) :
	IllegalStateException("Export requires explicit acknowledgement for $unresolvedCount unresolved sentences")

@Service
class ArtifactMarkdownExportService {
	fun render(
		sentences: List<ExportSentence>,
		evidence: List<EvidenceSnapshot>,
		acknowledgeUnresolved: Boolean,
		includeSources: Boolean,
		sources: List<ExportSource> = emptyList(),
	): MarkdownExport {
		val orderedSentences = sentences.sortedBy { it.orderIndex }
		if (orderedSentences.map { it.orderIndex }.distinct().size != orderedSentences.size) {
			throw IllegalArgumentException("Sentence order must be unique")
		}
		if (orderedSentences.any { it.body.isBlank() }) throw IllegalArgumentException("Export sentence is blank")
		val renderedSentences = orderedSentences.associate { sentence ->
			sentence.id to neutralizeUntrustedText(sentence.body.trim())
		}
		val unresolvedCount = orderedSentences.count { it.status.isUnresolved }
		if (unresolvedCount > 0 && !acknowledgeUnresolved) throw UnresolvedExportException(unresolvedCount)

		val evidenceById = evidence.associateBy { it.id }
		val markdown = buildString {
			append(renderedSentences.values.joinToString("\n\n"))
			if (includeSources) {
				val publicSources = sources
					.distinctBy { it.originalUrl }
					.mapNotNull { source ->
						val approvedUrl = approvedSourceUrl(source.provider, source.originalUrl) ?: return@mapNotNull null
						val label = neutralizeUntrustedText(source.sourceLabel.replace(NEWLINE, " ")).trim()
						if (label.isBlank() || evidenceById[source.evidenceId] == null) return@mapNotNull null
						"- [$label]($approvedUrl)"
					}
				if (publicSources.isNotEmpty()) {
					if (isNotEmpty()) append("\n\n")
					append("## Sources\n\n")
					append(publicSources.joinToString("\n"))
				}
			}
			if (isNotEmpty()) append('\n')
		}

		return MarkdownExport(
			markdown = markdown,
			unresolvedCount = unresolvedCount,
			warningAcknowledged = acknowledgeUnresolved && unresolvedCount > 0,
			renderedSentences = renderedSentences,
		)
	}

	private fun neutralizeUntrustedText(value: String): String = value
		.replace("&", "&amp;")
		.replace("<", "&lt;")
		.replace(">", "&gt;")
		.replace("\\", "\\\\")
		.replace("[", "\\[")
		.replace("]", "\\]")
		.replace(ACTIVE_SCHEME) { match -> "${match.groupValues[1]}&#58;" }

	private fun approvedSourceUrl(provider: String, value: String): String? {
		return try {
			val uri = URI(value)
			val host = uri.host?.lowercase() ?: return null
			if (uri.scheme?.lowercase() != "https" || uri.isOpaque || uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return null
			val approved = provider.uppercase() == "GITHUB" && host in setOf("github.com", "github.test")
			if (!approved) return null
			uri.toASCIIString()
				.replace("\\", "%5C")
				.replace("(", "%28")
				.replace(")", "%29")
				.takeIf { encoded -> encoded.none { it.isISOControl() || it == '<' || it == '>' || it == '"' || it == '\'' } }
		} catch (_: IllegalArgumentException) {
			null
		}
	}

	private companion object {
		val ACTIVE_SCHEME = Regex("(?i)\\b(https?|javascript|data)\\s*:")
		val NEWLINE = Regex("[\\r\\n]+")
	}
}

internal val ExportSentenceStatus.isUnresolved: Boolean
	get() = this in setOf(
		ExportSentenceStatus.NEEDS_SUPPORT,
		ExportSentenceStatus.CONFLICT,
		ExportSentenceStatus.USER_MODIFIED,
		ExportSentenceStatus.REVIEW_FAILED,
	)
