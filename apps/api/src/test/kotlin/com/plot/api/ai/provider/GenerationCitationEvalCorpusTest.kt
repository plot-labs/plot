package com.plot.api.ai.provider

import com.plot.api.generation.model.ReviewVerdict
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

class GenerationCitationEvalCorpusTest {
	private val mapper = ObjectMapper()

	@Test
	fun `eval corpus covers grounding risks without external URLs or secrets`() {
		val resource = requireNotNull(javaClass.getResource("/evals/generation-citation-cases.json"))
		val root = resource.openStream().use(mapper::readTree)
		val typed = resource.openStream().use { mapper.readValue(it, EvalCorpus::class.java) }
		val cases = requireNotNull(root["cases"]).asArray().values().toList()
		val tags = cases.flatMap { evalCase ->
			requireNotNull(evalCase["tags"]).asArray().values().map { it.stringValue() }
		}.toSet()

		assertEquals(
			setOf("supported", "unsupported", "non-factual", "multi-source", "conflict", "numeric", "date", "prompt-injection", "partial-rewrite"),
			tags,
		)
		assertTrue(cases.size >= 3)
		assertEquals(1, typed.version)
		assertEquals(cases.size, typed.cases.size)
		cases.forEach { evalCase ->
			assertTrue(evalCase["id"].stringValue().matches(Regex("[a-z0-9-]+")))
			val evidenceItems = requireNotNull(evalCase["evidence"]).asArray().values().toList()
			assertTrue(evidenceItems.size in 1..4)
			evidenceItems.forEach { evidence ->
				UUID.fromString(evidence["id"].stringValue())
				assertTrue(!evidence["body"].stringValue().contains("https://"))
			}
			requireNotNull(evalCase["sentences"]).asArray().values().forEach { sentence ->
				UUID.fromString(sentence["id"].stringValue())
				ReviewVerdict.valueOf(sentence["expectedVerdict"].stringValue())
			}
		}
	}
}
