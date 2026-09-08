package com.plot.api.artifact

import com.plot.api.content.ContentBriefDestination
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import tools.jackson.databind.ObjectMapper

class ArtifactDocumentValidatorTest {
	private val mapper = ObjectMapper()
	private val validator = ArtifactDocumentValidator(mapper)
	private val headingId = UUID.fromString("00000000-0000-0000-0000-000000000101")
	private val paragraphId = UUID.fromString("00000000-0000-0000-0000-000000000102")
	private val itemId = UUID.fromString("00000000-0000-0000-0000-000000000103")
	private val listId = UUID.fromString("00000000-0000-0000-0000-000000000104")
	private val ctaId = UUID.fromString("00000000-0000-0000-0000-000000000105")
	private val destinationId = UUID.fromString("00000000-0000-0000-0000-000000000106")

	@Test
	fun `accepts heading paragraph list and confirmed cta in statement order`() {
		val document = validator.validateAndSanitize(
			mapper.readTree(
				v2Document(
					"""
					{"type":"heading","nodeId":"$headingId","statementId":"$headingId","tag":"h1","children":[${text("Release notes")}],"version":1}
					""".trimIndent(),
					"""
					{"type":"paragraph","nodeId":"$paragraphId","statementId":"$paragraphId","children":[${text("Search is faster.")}],"version":1}
					""".trimIndent(),
					"""
					{"type":"list","nodeId":"$listId","listType":"bullet","start":1,"children":[{"type":"listItem","nodeId":"$itemId","statementId":"$itemId","children":[${text("Try the new search.")}],"version":1}],"version":1}
					""".trimIndent(),
					"""
					{"type":"cta","nodeId":"$ctaId","statementId":"$ctaId","destinationId":"$destinationId","destinationLabel":"Join the beta","destinationUrl":"https://plot.test/beta","children":[${text("Join the beta")}],"version":1}
					""".trimIndent(),
				),
			),
			listOf(
				NormalizedStatement(headingId, 0, "Release notes"),
				NormalizedStatement(paragraphId, 1, "Search is faster."),
				NormalizedStatement(itemId, 2, "Try the new search."),
				NormalizedStatement(ctaId, 3, "Join the beta"),
			),
			mapOf(destinationId to ContentBriefDestination(destinationId, "Join the beta", "https://plot.test/beta")),
		)

		assertEquals(2, document.get("documentVersion")?.asInt())
		val types: List<String> = document.get("root").get("children").toList().map { it.get("type").toString().trim('"') }
		assertEquals(
			listOf("heading", "paragraph", "list", "cta"),
			types,
		)
		assertEquals("listItem", document.get("root").get("children")[2].get("children")[0].get("type").asText())
	}

	@Test
	fun `rejects dangling statement duplicate node and unsafe cta`() {
		val statements = listOf(NormalizedStatement(headingId, 0, "Release notes"))
		val base = v2Document(
			"""{"type":"paragraph","nodeId":"$paragraphId","statementId":"$paragraphId","children":[${text("Release notes")}],"version":1 }""",
		)
		assertFailsWith<ArtifactDocumentValidationException> {
			validator.validateAndSanitize(mapper.readTree(base), statements)
		}

		val duplicateNode = v2Document(
			"""{"type":"paragraph","nodeId":"$paragraphId","statementId":"$headingId","children":[${text("Release notes")}],"version":1 }""",
			"""{"type":"paragraph","nodeId":"$paragraphId","statementId":"$headingId","children":[${text("Release notes")}],"version":1 }""",
		)
		assertFailsWith<ArtifactDocumentValidationException> {
			validator.validateAndSanitize(mapper.readTree(duplicateNode), statements)
		}

		val unsafeCta = v2Document(
			"""{"type":"cta","nodeId":"$ctaId","statementId":"$headingId","destinationId":"$destinationId","destinationLabel":"Release notes","destinationUrl":"javascript:alert(1)","children":[${text("Release notes")}],"version":1 }""",
		)
		assertFailsWith<ArtifactDocumentValidationException> {
			validator.validateAndSanitize(mapper.readTree(unsafeCta), statements)
		}
	}

	@Test
	fun `rejects an empty V2 document`() {
		assertFailsWith<ArtifactDocumentValidationException> {
			validator.validateAndSanitize(mapper.readTree(v2Document()), emptyList())
		}
	}

	private fun v2Document(vararg children: String): String = """
		{"documentVersion":2,"root":{"type":"root","version":1,"children":[${children.joinToString(",")}]}}
	""".trimIndent()

	private fun text(value: String): String = """
		{"detail":0,"format":0,"mode":"normal","style":"","text":"${value.replace("\"", "\\\"")}","type":"text","version":1}
	""".trimIndent()
}
