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
		val cases = requireNotNull(root["cases"]).asArray().values().toList()
		val tags = cases.flatMap { evalCase ->
			requireNotNull(evalCase["tags"]).asArray().values().map { it.stringValue() }
		}.toSet()

		assertEquals(
			setOf("supported", "unsupported", "non-factual", "multi-source", "conflict", "numeric", "date", "prompt-injection", "partial-rewrite"),
			tags,
		)
		assertTrue(cases.size >= 3)
		assertEquals(2, requireNotNull(root["version"]).intValue())
		val evidenceIds = mutableSetOf<UUID>()
		val sentenceIds = mutableSetOf<UUID>()
		cases.forEach { evalCase ->
			assertTrue(evalCase["id"].stringValue().matches(Regex("[a-z0-9-]+")))
			val evidenceItems = requireNotNull(evalCase["evidence"]).asArray().values().toList()
			assertTrue(evidenceItems.size in 1..4)
			evidenceItems.forEach { evidence ->
				assertTrue(evidenceIds.add(UUID.fromString(evidence["id"].stringValue())))
				assertTrue(!evidence["body"].stringValue().contains("https://"))
			}
			requireNotNull(evalCase["sentences"]).asArray().values().forEach { sentence ->
				assertTrue(sentenceIds.add(UUID.fromString(sentence["id"].stringValue())))
				ReviewVerdict.valueOf(sentence["expectedVerdict"].stringValue())
				val rewriteTarget = sentence["rewriteTarget"].booleanValue()
				val rewriteVerdict = sentence["rewriteExpectedVerdict"]
				assertEquals(rewriteTarget, rewriteVerdict != null && !rewriteVerdict.isNull)
				if (rewriteTarget) {
					assertEquals(ReviewVerdict.NEEDS_SUPPORT.name, sentence["expectedVerdict"].stringValue())
					ReviewVerdict.valueOf(rewriteVerdict.stringValue())
				}
			}
		}
	}

}
