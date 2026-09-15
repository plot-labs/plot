package com.plot.api.artifact

import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ExportSentence
import com.plot.api.artifact.workflow.model.ExportSentenceStatus
import com.plot.api.artifact.workflow.model.ExportSource
import com.plot.api.artifact.workflow.model.MarkdownExport
import java.net.URI
import java.util.UUID
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode

class UnresolvedExportException(val unresolvedCount: Int) :
	IllegalStateException("Export requires explicit acknowledgement for $unresolvedCount unresolved sentences")

@Service
class ArtifactMarkdownExportService {
	fun renderDocument(
		document: JsonNode,
		sentences: List<ExportSentence>,
		evidence: List<EvidenceSnapshot>,
		acknowledgeUnresolved: Boolean,
		includeSources: Boolean,
		sources: List<ExportSource> = emptyList(),
	): MarkdownExport {
		if (document.get("documentVersion")?.asInt() != 2) {
			return render(sentences, evidence, acknowledgeUnresolved, includeSources, sources)
		}
		val base = render(sentences, evidence, acknowledgeUnresolved, includeSources = false)
		val sentenceBodies = base.renderedSentences
		val root = document.get("root") ?: throw IllegalArgumentException("V2 document root is missing")
		val markdownBody = renderV2Children(root.get("children"), sentenceBodies)
		val markdown = buildString {
			append(markdownBody.trimEnd())
			if (includeSources) appendSourceSections(this, evidence, sources)
			if (isNotEmpty()) append('\n')
		}
		return base.copy(markdown = markdown)
	}

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

		val markdown = buildString {
			append(renderedSentences.values.joinToString("\n\n"))
			if (includeSources) appendSourceSections(this, evidence, sources)
			if (isNotEmpty()) append('\n')
		}

		return MarkdownExport(
			markdown = markdown,
			unresolvedCount = unresolvedCount,
			warningAcknowledged = acknowledgeUnresolved && unresolvedCount > 0,
			renderedSentences = renderedSentences,
		)
	}

	private fun renderV2Children(children: JsonNode?, sentenceBodies: Map<UUID, String>): String {
		if (children == null || !children.isArray) throw IllegalArgumentException("V2 document children must be an array")
		return children.mapIndexed { index, child -> renderV2Node(child, "V2 block $index", sentenceBodies) }
			.joinToString("\n\n")
	}

	private fun renderV2Node(node: JsonNode, path: String, sentenceBodies: Map<UUID, String>): String {
		val type = node.get("type")?.asText() ?: throw IllegalArgumentException("$path type is missing")
		if (type == "list") {
			val children = node.get("children")
			if (children == null || !children.isArray) throw IllegalArgumentException("$path children must be an array")
			val ordered = node.get("listType")?.asText() == "ordered"
			val start = node.get("start")?.asInt() ?: 1
			return children.mapIndexed { index, item ->
				val body = renderV2Node(item, "$path item $index", sentenceBodies)
				if (ordered) "${start + index}. $body" else "- $body"
			}.joinToString("\n")
		}
		val statementId = node.get("statementId")?.asText()?.let { parseUuid(it, "$path statementId") }
			?: throw IllegalArgumentException("$path statementId is missing")
		val body = sentenceBodies[statementId] ?: throw IllegalArgumentException("$path statement body is missing")
		return when (type) {
			"heading" -> {
				val tag = node.get("tag")?.asText() ?: throw IllegalArgumentException("$path heading tag is missing")
				"#".repeat(tag.removePrefix("h").toIntOrNull()?.coerceIn(1, 3) ?: 2) + " " + body
			}
			"paragraph", "listItem" -> body
			"cta" -> {
				val label = node.get("destinationLabel")?.asText()?.trim().takeUnless { it.isNullOrBlank() } ?: body
				val url = approvedCtaUrl(node.get("destinationUrl")?.asText())
				if (url == null) body else "[${neutralizeUntrustedText(label)}]($url)"
			}
			else -> throw IllegalArgumentException("$path has unsupported type '$type'")
		}
	}

	private fun parseUuid(value: String, path: String): UUID = try {
		UUID.fromString(value)
	} catch (_: IllegalArgumentException) {
		throw IllegalArgumentException("$path must be a UUID")
	}

	private fun appendSourceSections(builder: StringBuilder, evidence: List<EvidenceSnapshot>, sources: List<ExportSource>) {
		val evidenceById = evidence.associateBy { it.id }
		val publicSources = sources
			.distinctBy { it.originalUrl ?: it.evidenceId }
			.mapNotNull { source ->
				val approvedUrl = approvedSourceUrl(source.provider, source.originalUrl) ?: return@mapNotNull null
				val label = neutralizeUntrustedText(source.sourceLabel.replace(NEWLINE, " ")).trim()
				if (label.isBlank() || evidenceById[source.evidenceId] == null) return@mapNotNull null
				"- [$label]($approvedUrl)"
			}
		val confirmedSources = sources
			.filter { it.provider.equals("USER_CONFIRMED", ignoreCase = true) }
			.distinctBy { it.evidenceId }
			.mapNotNull { source ->
				val label = neutralizeUntrustedText(source.sourceLabel.replace(NEWLINE, " ")).trim()
				if (label.isBlank() || evidenceById[source.evidenceId] == null) return@mapNotNull null
				"- $label"
			}
		if (publicSources.isNotEmpty()) {
			if (builder.isNotEmpty()) builder.append("\n\n")
			builder.append("## Sources\n\n").append(publicSources.joinToString("\n"))
		}
		if (confirmedSources.isNotEmpty()) {
			if (builder.isNotEmpty()) builder.append("\n\n")
			builder.append("## Confirmed in Plot\n\n").append(confirmedSources.joinToString("\n"))
		}
	}

	private fun neutralizeUntrustedText(value: String): String {
		val escaped = value
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\\", "\\\\")
			.replace("[", "\\[")
			.replace("]", "\\]")
			.replace("`", "\\`")
			.replace("*", "\\*")
			.replace("_", "\\_")
			.replace("~", "\\~")
			.replace("|", "\\|")
			.replace(ACTIVE_SCHEME) { match -> "${match.groupValues[1]}&#58;" }
		return BLOCK_MARKER.replace(escaped) { match ->
			val indentation = match.groupValues[1]
			val marker = match.value.removePrefix(indentation)
			val escapedMarker = if (marker.firstOrNull()?.isDigit() == true) {
				marker.dropLast(1) + "\\" + marker.last()
			} else {
				"\\" + marker
			}
			indentation + escapedMarker
		}.replace(INDENTED_CODE) { "&#32;" + it.value.drop(1) }
	}

	private fun approvedSourceUrl(provider: String, value: String?): String? {
		if (value.isNullOrBlank()) return null
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

	private fun approvedCtaUrl(value: String?): String? {
		if (value.isNullOrBlank()) return null
		return try {
			val uri = URI(value.trim())
			if (uri.scheme?.lowercase() != "https" || uri.isOpaque || uri.host.isNullOrBlank() || uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return null
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
		val BLOCK_MARKER = Regex("(?m)^( {0,3})(?:(?:#{1,6})(?=\\s)|>(?=\\s?)|[-+*](?=\\s)|```|~~~|\\d+[.)](?=\\s)|(?:-{3,}|\\*{3,}|_{3,}|={3,})(?=\\s*$))")
		val INDENTED_CODE = Regex("(?m)^ {4}")
	}
}

internal val ExportSentenceStatus.isUnresolved: Boolean
	get() = this in setOf(
		ExportSentenceStatus.NEEDS_SUPPORT,
		ExportSentenceStatus.CONFLICT,
		ExportSentenceStatus.USER_MODIFIED,
		ExportSentenceStatus.REVIEW_FAILED,
	)
