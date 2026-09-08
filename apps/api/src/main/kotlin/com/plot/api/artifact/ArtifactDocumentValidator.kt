package com.plot.api.artifact

import java.net.URI
import java.util.UUID
import com.plot.api.content.ContentBriefDestination
import com.plot.api.content.isSafeCtaLabel
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Validates the application-owned document contract used by artifact
 * revisions. The existing Lexical-shaped V1 document is validated by
 * [ArtifactLexicalDocumentValidator]; this class owns the additive V2 grammar.
 */
class ArtifactDocumentValidator(
	private val objectMapper: ObjectMapper,
) {
	internal fun documentVersion(document: JsonNode): Int {
		if (!document.isObject) invalid("Lexical content must be a JSON object")
		val value = document.get("documentVersion") ?: return 1
		if (!value.isIntegralNumber || !value.canConvertToInt() || value.asInt() != V2_VERSION) {
			invalid("Unsupported document version")
		}
		return V2_VERSION
	}

	internal fun validateAndSanitize(
		document: JsonNode,
		statements: List<NormalizedStatement>,
		allowedDestinations: Map<UUID, ContentBriefDestination> = emptyMap(),
	): JsonNode {
		if (documentVersion(document) != V2_VERSION) invalid("Document is not a V2 document")
		assertAllowedFields(document, DOCUMENT_FIELDS, "Lexical content")
		val root = document.get("root") ?: invalid("Lexical content must contain a root node")
		if (!root.isObject) invalid("Lexical root must be an object")
		assertAllowedFields(root, ROOT_FIELDS, "Lexical root")
		requireType(root, "root", "Lexical root")
		requireVersion(root, "Lexical root")
		val children = requireArray(root, "children", "Lexical root")
		if (children.isEmpty) invalid("V2 document must contain at least one block")

		val nodeIds = linkedSetOf<UUID>()
		val statementIds = mutableListOf<UUID>()
		val statementBodies = mutableListOf<String>()
		val sanitizedRoot = objectMapper.createObjectNode().apply {
			putNull("direction")
			put("format", "")
			put("indent", 0)
			put("type", "root")
			put("version", 1)
		}
		val sanitizedChildren = sanitizedRoot.putArray("children")
		children.forEachIndexed { index, child ->
			sanitizedChildren.add(
					sanitizeTopLevelBlock(child, "Lexical block $index", nodeIds, statementIds, statementBodies, allowedDestinations),
			)
		}

		val expectedStatements = statements.sortedBy { it.orderIndex }
		if (statementIds != expectedStatements.map { it.id }) {
			invalid("V2 layout must reference each statement exactly once in order")
		}
		if (statementBodies != expectedStatements.map { it.body }) {
			invalid("V2 layout text must exactly match statement order and content")
		}

		return objectMapper.createObjectNode().apply {
			put("documentVersion", V2_VERSION)
			set("root", sanitizedRoot)
		}
	}

	private fun sanitizeTopLevelBlock(
		node: JsonNode,
		path: String,
		nodeIds: MutableSet<UUID>,
		statementIds: MutableList<UUID>,
		statementBodies: MutableList<String>,
		allowedDestinations: Map<UUID, ContentBriefDestination>,
	): JsonNode {
		val type = nodeType(node, path)
		return when (type) {
			"heading" -> {
				val tag = node.get("tag")?.takeIf { it.isTextual }?.asText()
					?: invalid("$path heading tag is required")
				if (tag !in HEADING_TAGS) invalid("$path heading tag is unsupported")
				(sanitizeStatementBlock(node, path, "heading", nodeIds, statementIds, statementBodies) as ObjectNode)
					.apply { put("tag", tag) }
			}
			"paragraph" -> sanitizeStatementBlock(node, path, "paragraph", nodeIds, statementIds, statementBodies)
			"list" -> sanitizeList(node, path, nodeIds, statementIds, statementBodies, allowedDestinations)
			"cta" -> sanitizeCta(node, path, nodeIds, statementIds, statementBodies, allowedDestinations)
			else -> invalid("$path has unsupported type '$type'")
		}
	}

	private fun sanitizeList(
		node: JsonNode,
		path: String,
		nodeIds: MutableSet<UUID>,
		statementIds: MutableList<UUID>,
		statementBodies: MutableList<String>,
		allowedDestinations: Map<UUID, ContentBriefDestination>,
	): JsonNode {
		assertAllowedFields(node, LIST_FIELDS, path)
		requireType(node, "list", path)
		requireVersion(node, path)
		val nodeId = requireUuid(node, "nodeId", path)
		if (!nodeIds.add(nodeId)) invalid("$path has a duplicate nodeId")
		val listType = node.get("listType")?.takeIf { it.isTextual }?.asText()
			?: invalid("$path listType is required")
		if (listType !in LIST_TYPES) invalid("$path listType is unsupported")
		val start = requireNonNegativeInt(node, "start", path)
		if (start < 1) invalid("$path start must be positive")
		val children = requireArray(node, "children", path)
		if (children.isEmpty) invalid("$path must contain at least one list item")
		val sanitized = objectMapper.createObjectNode().apply {
			put("nodeId", nodeId.toString())
			put("listType", listType)
			put("start", start)
			put("type", "list")
			put("version", 1)
		}
		val sanitizedChildren = sanitized.putArray("children")
		children.forEachIndexed { index, child ->
			if (nodeType(child, "$path item $index") != "listItem") {
				invalid("$path item $index must have type 'listItem'")
			}
			sanitizedChildren.add(
				sanitizeStatementBlock(child, "$path item $index", "listItem", nodeIds, statementIds, statementBodies),
			)
		}
		return sanitized
	}

	private fun sanitizeCta(
		node: JsonNode,
		path: String,
		nodeIds: MutableSet<UUID>,
		statementIds: MutableList<UUID>,
		statementBodies: MutableList<String>,
		allowedDestinations: Map<UUID, ContentBriefDestination>,
	): JsonNode {
		assertAllowedFields(node, CTA_FIELDS, path)
		val sanitized = sanitizeStatementBlock(node, path, "cta", nodeIds, statementIds, statementBodies)
		val destinationId = requireUuid(node, "destinationId", path)
		val destinationLabel = node.get("destinationLabel")?.takeIf { it.isTextual }?.asText()?.trim()
			?.takeIf(::isSafeCtaLabel)
			?: invalid("$path destinationLabel must be a non-blank label")
		val destinationUrl = node.get("destinationUrl")?.takeIf { it.isTextual }?.asText()?.trim()
			?.takeIf(::isSafeCtaUrl)
			?: invalid("$path destinationUrl must be an absolute HTTPS URL")
		val confirmed = allowedDestinations[destinationId]
			?: invalid("$path destinationId is not a confirmed destination")
		if (confirmed.label != destinationLabel || confirmed.url != destinationUrl) {
			invalid("$path destination does not match the confirmed destination")
		}
		val body = lexicalNodeText(sanitized.get("children"), path).trim()
		if (body != destinationLabel) invalid("$path destinationLabel must match its statement text")
		return (sanitized as ObjectNode).apply {
			put("destinationId", destinationId.toString())
			put("destinationLabel", destinationLabel)
			put("destinationUrl", destinationUrl)
		}
	}

	private fun sanitizeStatementBlock(
		node: JsonNode,
		path: String,
		type: String,
		nodeIds: MutableSet<UUID>,
		statementIds: MutableList<UUID>,
		statementBodies: MutableList<String>,
	): JsonNode {
		assertAllowedFields(node, when (type) {
			"heading" -> HEADING_FIELDS
			"paragraph" -> PARAGRAPH_FIELDS
			"listItem" -> LIST_ITEM_FIELDS
			"cta" -> CTA_FIELDS
			else -> error("Unsupported statement block type")
		}, path)
		requireType(node, type, path)
		requireVersion(node, path)
		val nodeId = requireUuid(node, "nodeId", path)
		if (!nodeIds.add(nodeId)) invalid("$path has a duplicate nodeId")
		val statementId = requireUuid(node, "statementId", path)
		if (!statementIds.addIfAbsent(statementId)) invalid("$path has a duplicate statementId")
		val children = requireArray(node, "children", path)
		val sanitizedChildren = sanitizeTextChildren(children, path)
		val body = lexicalNodeText(sanitizedChildren, path).trim()
		if (body.isBlank()) invalid("$path text is blank")
		statementBodies += body
		return objectMapper.createObjectNode().apply {
			put("nodeId", nodeId.toString())
			put("statementId", statementId.toString())
			set("children", sanitizedChildren)
			putNull("direction")
			put("format", "")
			put("indent", 0)
			put("type", type)
			put("version", 1)
		}
	}

	private fun sanitizeTextChildren(children: JsonNode, path: String): JsonNode {
		val sanitized = objectMapper.createArrayNode()
		children.forEachIndexed { index, child ->
			val childPath = "$path child $index"
			if (!child.isObject) invalid("$childPath must be an object")
			when (nodeType(child, childPath)) {
				"text" -> {
					assertAllowedFields(child, TEXT_FIELDS, childPath)
					val text = child.get("text")?.takeIf { it.isTextual }?.asText()
						?: invalid("$childPath text must be a string")
					requireVersion(child, childPath)
					val detail = requireNonNegativeInt(child, "detail", childPath)
					val format = requireNonNegativeInt(child, "format", childPath)
					val mode = child.get("mode")?.takeIf { it.isTextual }?.asText()
						?.takeIf { it in TEXT_MODES } ?: invalid("$childPath mode is unsupported")
					val style = child.get("style")?.takeIf { it.isTextual }?.asText()
						?: invalid("$childPath style must be a string")
					sanitized.add(objectMapper.createObjectNode().apply {
						put("detail", detail)
						put("format", format)
						put("mode", mode)
						put("style", style)
						put("text", text)
						put("type", "text")
						put("version", 1)
					})
				}
				"linebreak" -> {
					assertAllowedFields(child, LINEBREAK_FIELDS, childPath)
					requireVersion(child, childPath)
					sanitized.add(objectMapper.createObjectNode().apply {
						put("type", "linebreak")
						put("version", 1)
					})
				}
				else -> invalid("$childPath has unsupported type")
			}
		}
		return sanitized
	}

	private fun lexicalNodeText(children: JsonNode, path: String): String = children.mapIndexed { index, child ->
		when (child.get("type")?.asText()) {
			"text" -> child.get("text")?.asText() ?: invalid("$path child $index text is missing")
			"linebreak" -> "\n"
			else -> invalid("$path child $index has unsupported type")
		}
	}.joinToString("")

	private fun nodeType(node: JsonNode, path: String): String {
		if (!node.isObject) invalid("$path must be an object")
		return node.get("type")?.takeIf { it.isTextual }?.asText()
			?: invalid("$path is missing a node type")
	}

	private fun requireType(node: JsonNode, expected: String, path: String) {
		if (nodeType(node, path) != expected) invalid("$path must have type '$expected'")
	}

	private fun requireVersion(node: JsonNode, path: String) {
		val value = node.get("version")
		if (value == null || !value.isIntegralNumber || !value.canConvertToInt() || value.asInt() != 1) {
			invalid("$path must have version 1")
		}
	}

	private fun requireUuid(node: JsonNode, field: String, path: String): UUID {
		val value = node.get(field)?.takeIf { it.isTextual }?.asText()
			?: invalid("$path $field must be a UUID")
		return try {
			UUID.fromString(value)
		} catch (_: IllegalArgumentException) {
			invalid("$path $field must be a UUID")
		}
	}

	private fun requireArray(node: JsonNode, field: String, path: String): JsonNode {
		val value = node.get(field)
		if (value == null || !value.isArray) invalid("$path $field must be an array")
		return value
	}

	private fun requireNonNegativeInt(node: JsonNode, field: String, path: String): Int {
		val value = node.get(field)
		if (value == null || !value.isIntegralNumber || !value.canConvertToInt() || value.asInt() < 0) {
			invalid("$path $field must be a nonnegative integer")
		}
		return value.asInt()
	}

	private fun assertAllowedFields(node: JsonNode, allowed: Set<String>, path: String) {
		node.propertyNames().firstOrNull { it !in allowed }?.let { invalid("$path contains unsupported field '$it'") }
	}

	private fun isSafeCtaUrl(value: String): Boolean = try {
		val uri = URI(value)
		uri.scheme?.lowercase() == "https" && !uri.isOpaque && !uri.host.isNullOrBlank() &&
			uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
			value.none { it.isISOControl() || it == '<' || it == '>' || it == '"' || it == '\'' }
	} catch (_: IllegalArgumentException) {
		false
	}

	private fun invalid(message: String): Nothing = throw ArtifactDocumentValidationException(message)

	private companion object {
		const val V2_VERSION = 2
		val DOCUMENT_FIELDS = setOf("documentVersion", "root")
		val ROOT_FIELDS = setOf("children", "direction", "format", "indent", "type", "version")
		val HEADING_FIELDS = setOf("children", "direction", "format", "indent", "nodeId", "statementId", "tag", "type", "version")
		val PARAGRAPH_FIELDS = setOf("children", "direction", "format", "indent", "nodeId", "statementId", "type", "version")
		val LIST_FIELDS = setOf("children", "nodeId", "listType", "start", "type", "version")
		val LIST_ITEM_FIELDS = setOf("children", "direction", "format", "indent", "nodeId", "statementId", "type", "version")
		val CTA_FIELDS = setOf("children", "destinationId", "destinationLabel", "destinationUrl", "direction", "format", "indent", "nodeId", "statementId", "type", "version")
		val TEXT_FIELDS = setOf("detail", "format", "mode", "style", "text", "type", "version")
		val LINEBREAK_FIELDS = setOf("type", "version")
		val HEADING_TAGS = setOf("h1", "h2", "h3")
		val LIST_TYPES = setOf("bullet", "ordered")
		val TEXT_MODES = setOf("normal", "token", "segmented")
	}
}

internal class ArtifactDocumentValidationException(message: String) : IllegalArgumentException(message)

private fun MutableList<UUID>.addIfAbsent(value: UUID): Boolean {
	if (value in this) return false
	add(value)
	return true
}
