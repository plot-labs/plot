package com.plot.api.artifact

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.artifact.dto.ContentCitationResponse
import com.plot.api.artifact.dto.ContentExportResponse
import com.plot.api.artifact.dto.ArtifactPageResponse
import com.plot.api.artifact.dto.ArtifactResponse
import com.plot.api.artifact.dto.ArtifactSummaryResponse
import com.plot.api.artifact.dto.ContentSentenceResponse
import com.plot.api.artifact.dto.ContentSourceResponse
import com.plot.api.artifact.dto.ContentStatementInput
import com.plot.api.artifact.dto.ContentVariantResponse
import com.plot.api.artifact.dto.ContentVariantHistoryItemResponse
import com.plot.api.artifact.dto.ContentVariantHistoryDetailResponse
import com.plot.api.artifact.dto.ExportDisposition
import com.plot.api.artifact.dto.ExportWarningResponse
import com.plot.api.dev.DevContext
import com.plot.api.artifact.workflow.model.CitationStatus
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ExportSentence
import com.plot.api.artifact.workflow.model.ExportSentenceStatus
import com.plot.api.artifact.workflow.model.SentenceCitation
import com.plot.api.artifact.workflow.model.SourceProvider
import com.plot.api.artifact.workflow.model.ExportSource
import java.net.URI
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.util.HexFormat
import java.util.UUID
import org.springframework.http.HttpStatus
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import com.plot.api.persistence.SqlRow
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper



@Service
class ArtifactLexicalDocumentValidator(
    private val sqlExecutor: JooqSqlExecutor,
    private val uuidGenerator: UuidGenerator,
    private val objectMapper: ObjectMapper,
    private val devContext: DevContext,
) {
	internal fun normalizeStatements(statements: List<ContentStatementInput>): List<NormalizedStatement> = statements.mapIndexed { index, input ->
		val id = input.id ?: uuidGenerator.next()
		val order = input.orderIndex ?: throw badRequest("Statement order is required")
		if (order < 0) throw badRequest("Statement order must not be negative")
		val body = input.body?.trim()?.takeIf { it.isNotBlank() }
			?: throw badRequest("Statement body is required")
		NormalizedStatement(id, order, body)
	}.also { rows ->
		if (rows.map { it.id }.distinct().size != rows.size) {
			throw badRequest("Statement IDs must be unique")
		}
		if (rows.map { it.orderIndex }.distinct().size != rows.size) {
			throw badRequest("Statement order must be unique")
		}
	}

	internal fun validateStatementOwnership(statements: List<NormalizedStatement>, allSentenceIds: Set<UUID>) {
		statements.filter { it.id !in allSentenceIds }.forEach { statement ->
			// New application-owned IDs are valid; only reject an ID that claims to
			// belong to another artifact when its UUID already exists elsewhere.
			val belongsToAnotherArtifact = sqlExecutor.queryForObject(
				"select exists (select 1 from content_variant_sentences where id = ?)",
				Boolean::class.java,
				statement.id,
			) ?: false
			if (belongsToAnotherArtifact) {
				throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Statement belongs to another artifact", statement.id)
			}
		}
	}

	internal fun validateAndSanitizeLexicalContent(lexicalContent: JsonNode, statements: List<NormalizedStatement>): JsonNode {
		if (!lexicalContent.isObject) throw badRequest("Lexical content must be a JSON object")
		assertAllowedFields(lexicalContent, setOf("root"), "Lexical content")
		val root = lexicalContent.get("root")
			?: throw badRequest("Lexical content must contain a root node")
		if (!root.isObject) throw badRequest("Lexical root must be an object")
		assertAllowedFields(root, ROOT_FIELDS, "Lexical root")
		requireNodeType(root, "root", "Lexical root")
		requireNodeVersion(root, "Lexical root")
		val children = requireArray(root, "children", "Lexical root")
		val sanitizedRoot = objectMapper.createObjectNode()
		val sanitizedChildren = sanitizedRoot.putArray("children")
		val lexicalBodies = mutableListOf<String>()
		children.forEachIndexed { index, child ->
			val path = "Lexical statement block $index"
			val sanitizedParagraph = sanitizeParagraph(child, path)
			sanitizedChildren.add(sanitizedParagraph)
			lexicalBodies += lexicalNodeText(sanitizedParagraph, path)
		}
		copyDirection(root, sanitizedRoot, "Lexical root")
		sanitizedRoot.put("format", requireFormat(root, "Lexical root"))
		sanitizedRoot.put("indent", requireNonNegativeInt(root, "indent", "Lexical root"))
		sanitizedRoot.put("type", "root")
		sanitizedRoot.put("version", 1)

		val statementBodies = statements.sortedBy { it.orderIndex }.map { it.body }
		if (lexicalBodies != statementBodies) {
			throw badRequest("Lexical content must exactly match statement order and content")
		}
		return objectMapper.createObjectNode().set("root", sanitizedRoot)
	}

	private fun sanitizeParagraph(node: JsonNode, path: String): JsonNode {
		if (!node.isObject) throw badRequest("$path must be an object")
		assertAllowedFields(node, PARAGRAPH_FIELDS, path)
		requireNodeType(node, "paragraph", path)
		requireNodeVersion(node, path)
		val children = requireArray(node, "children", path)
		val sanitized = objectMapper.createObjectNode()
		val sanitizedChildren = sanitized.putArray("children")
		children.forEachIndexed { index, child ->
			val childPath = "$path child $index"
			if (!child.isObject) throw badRequest("$childPath must be an object")
			val type = child.get("type")
				?.takeIf { it.isTextual }
				?.asText()
				?: throw badRequest("$childPath is missing a node type")
			when (type) {
				"text" -> sanitizedChildren.add(sanitizeText(child, childPath))
				"linebreak" -> sanitizedChildren.add(sanitizeLinebreak(child, childPath))
				else -> throw badRequest("$childPath has unsupported type '$type'")
			}
		}
		copyDirection(node, sanitized, path)
		sanitized.put("format", requireFormat(node, path))
		sanitized.put("indent", requireNonNegativeInt(node, "indent", path))
		node.get("textFormat")?.let {
			if (!it.isIntegralNumber || !it.canConvertToInt() || it.asInt() < 0) {
				throw badRequest("$path textFormat must be a nonnegative integer")
			}
			sanitized.put("textFormat", it.asInt())
		}
		node.get("textStyle")?.let {
			if (!it.isTextual) throw badRequest("$path textStyle must be a string")
			sanitized.put("textStyle", it.asText())
		}
		sanitized.put("type", "paragraph")
		sanitized.put("version", 1)
		return sanitized
	}

	private fun sanitizeText(node: JsonNode, path: String): JsonNode {
		assertAllowedFields(node, TEXT_FIELDS, path)
		requireNodeType(node, "text", path)
		requireNodeVersion(node, path)
		val text = node.get("text")
			?.takeIf { it.isTextual }
			?.asText()
			?: throw badRequest("$path text must be a string")
		val detail = requireNonNegativeInt(node, "detail", path)
		val format = requireNonNegativeInt(node, "format", path)
		val mode = node.get("mode")
			?.takeIf { it.isTextual }
			?.asText()
			?.takeIf { it in TEXT_MODES }
			?: throw badRequest("$path mode must be normal, token, or segmented")
		val style = node.get("style")
			?.takeIf { it.isTextual }
			?.asText()
			?: throw badRequest("$path style must be a string")
		return objectMapper.createObjectNode().apply {
			put("detail", detail)
			put("format", format)
			put("mode", mode)
			put("style", style)
			put("text", text)
			put("type", "text")
			put("version", 1)
		}
	}

	private fun sanitizeLinebreak(node: JsonNode, path: String): JsonNode {
		assertAllowedFields(node, LINEBREAK_FIELDS, path)
		requireNodeType(node, "linebreak", path)
		requireNodeVersion(node, path)
		return objectMapper.createObjectNode().apply {
			put("type", "linebreak")
			put("version", 1)
		}
	}

	private fun lexicalNodeText(node: JsonNode, path: String): String {
		val type = node.get("type")?.asText() ?: throw badRequest("$path is missing a node type")
		if (type == "text") return node.get("text")?.asText() ?: throw badRequest("$path text is missing")
		if (type == "linebreak") return "\n"
		val children = node.get("children") ?: throw badRequest("$path children are missing")
		return children.mapIndexed { index, child -> lexicalNodeText(child, "$path child $index") }.joinToString("")
	}

	private fun assertAllowedFields(node: JsonNode, allowed: Set<String>, path: String) {
		node.propertyNames().filter { it !in allowed }.firstOrNull()?.let { field ->
			throw badRequest("$path contains unsupported field '$field'")
		}
	}

	private fun requireArray(node: JsonNode, field: String, path: String): JsonNode {
		val value = node.get(field)
		if (value == null || !value.isArray) throw badRequest("$path $field must be an array")
		return value
	}

	private fun requireNodeType(node: JsonNode, expected: String, path: String) {
		val value = node.get("type")
		if (value == null || !value.isTextual || value.asText() != expected) {
			throw badRequest("$path must have type '$expected'")
		}
	}

	private fun requireNodeVersion(node: JsonNode, path: String) {
		val value = node.get("version")
		if (value == null || !value.isIntegralNumber || !value.canConvertToInt() || value.asInt() != 1) {
			throw badRequest("$path must have version 1")
		}
	}

	private fun requireNonNegativeInt(node: JsonNode, field: String, path: String): Int {
		val value = node.get(field)
		if (value == null || !value.isIntegralNumber || !value.canConvertToInt() || value.asInt() < 0) {
			throw badRequest("$path $field must be a nonnegative integer")
		}
		return value.asInt()
	}

	private fun requireFormat(node: JsonNode, path: String): String {
		val value = node.get("format")
		if (value == null || !value.isTextual || value.asText() !in ELEMENT_FORMATS) {
			throw badRequest("$path format is unsupported")
		}
		return value.asText()
	}

	private fun copyDirection(node: JsonNode, target: tools.jackson.databind.node.ObjectNode, path: String) {
		val value = node.get("direction") ?: throw badRequest("$path direction is required")
		if (value.isNull) {
			target.putNull("direction")
		} else if (value.isTextual && value.asText() in DIRECTIONS) {
			target.put("direction", value.asText())
		} else {
			throw badRequest("$path direction must be null, ltr, or rtl")
		}
	}

	internal fun badRequest(message: String): ApiException =
		ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message)



    private companion object {
        val ROOT_FIELDS = setOf("children", "direction", "format", "indent", "type", "version")
        val PARAGRAPH_FIELDS = setOf("children", "direction", "format", "indent", "textFormat", "textStyle", "type", "version")
        val TEXT_FIELDS = setOf("detail", "format", "mode", "style", "text", "type", "version")
        val LINEBREAK_FIELDS = setOf("type", "version")
        val DIRECTIONS = setOf("ltr", "rtl")
        val ELEMENT_FORMATS = setOf("", "left", "start", "center", "right", "end", "justify")
        val TEXT_MODES = setOf("normal", "token", "segmented")
    }
}

internal data class NormalizedStatement(val id: UUID, val orderIndex: Int, val body: String)
