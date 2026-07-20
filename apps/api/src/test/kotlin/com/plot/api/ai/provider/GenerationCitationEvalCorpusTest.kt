package com.plot.api.ai.provider

import com.plot.api.certification.CertificationCaseEvaluator
import com.plot.api.certification.CertificationCaseObservation
import com.plot.api.certification.CertificationReviewObservation
import com.plot.api.certification.CertificationReviewVerdict
import com.plot.api.certification.CertificationSentenceOracle
import com.plot.api.certification.EvidenceOutcome
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
		assertEquals(2, typed.version)
		assertEquals(cases.size, typed.cases.size)
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

	@Test
	fun `a perfect oracle projection passes every case without aggregate thresholds`() {
		val corpus = requireNotNull(javaClass.getResource("/evals/generation-citation-cases.json"))
			.openStream().use { mapper.readValue(it, EvalCorpus::class.java) }
		val evaluator = CertificationCaseEvaluator()

		corpus.cases.forEach { evalCase ->
			val oracles = evalCase.sentences.map { sentence ->
				CertificationSentenceOracle(
					UUID.fromString(sentence.id),
					CertificationReviewVerdict.valueOf(sentence.expectedVerdict.name),
					sentence.expectedEvidenceIds.toSet(),
					sentence.rewriteExpectedVerdict?.let { CertificationReviewVerdict.valueOf(it.name) },
					sentence.rewriteExpectedEvidenceIds.toSet(),
				)
			}
			val initial = oracles.map { oracle ->
				CertificationReviewObservation(oracle.sentenceId, oracle.expectedVerdict, oracle.expectedEvidenceIds)
			}
			val rewritten = oracles.filter { it.rewriteExpectedVerdict != null }.map { oracle ->
				CertificationReviewObservation(
					oracle.sentenceId,
					requireNotNull(oracle.rewriteExpectedVerdict),
					oracle.rewriteExpectedEvidenceIds,
				)
			}.takeIf(List<*>::isNotEmpty)
			val grade = evaluator.evaluate(
				CertificationCaseObservation(
					scenarioId = evalCase.id,
					knownEvidenceIds = evalCase.evidence.map { UUID.fromString(it.id) }.toSet(),
					sentenceOracles = oracles,
					initialReviews = initial,
					rewrittenReviews = rewritten,
				),
			)

			assertEquals(EvidenceOutcome.PASS, grade.outcome, evalCase.id)
		}
	}
}
