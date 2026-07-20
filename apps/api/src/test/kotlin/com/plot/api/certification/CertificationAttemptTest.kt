package com.plot.api.certification

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CertificationAttemptTest {
	private val sentenceId = UUID.fromString("20000000-0000-0000-0000-000000000001")
	private val evidenceId = UUID.fromString("10000000-0000-0000-0000-000000000001")

	@Test
	fun `three valid slots ignore inconclusive history and preserve replacement lineage`() {
		val ids = ArrayDeque(listOf(
			"attempt-1111111111111111",
			"attempt-2222222222222222",
			"attempt-3333333333333333",
			"attempt-4444444444444444",
		))
		val matrix = CertificationAttemptMatrix(idGenerator = ids::removeFirst)

		val interrupted = matrix.next()
		matrix.record(interrupted, EvidenceOutcome.INCONCLUSIVE)
		val replacement = matrix.next()
		matrix.record(replacement, EvidenceOutcome.PASS)
		val second = matrix.next()
		matrix.record(second, EvidenceOutcome.HARD_GATE_FAIL)
		val third = matrix.next()
		matrix.record(third, EvidenceOutcome.PASS)

		assertEquals(listOf(1, 1, 2, 3), matrix.history.map { it.ordinal })
		assertEquals(interrupted.attemptId, replacement.replaces?.attemptId)
		assertEquals(3, matrix.validAttemptCount)
		assertTrue(matrix.complete)
		assertFailsWith<IllegalStateException> { matrix.next() }
	}

	@Test
	fun `only the immediately preceding inconclusive attempt can be replaced`() {
		val ids = ArrayDeque(listOf("attempt-1111111111111111", "attempt-2222222222222222"))
		val matrix = CertificationAttemptMatrix(idGenerator = ids::removeFirst)
		val failed = matrix.next()
		matrix.record(failed, EvidenceOutcome.HARD_GATE_FAIL)

		val next = matrix.next()

		assertEquals(2, next.ordinal)
		assertEquals(null, next.replaces)
		assertFailsWith<IllegalArgumentException> { matrix.record(failed, EvidenceOutcome.PASS) }
	}

	@Test
	fun `fabricated citations unsupported confirmations and missed conflicts are hard gates`() {
		val evaluator = CertificationCaseEvaluator()
		val cases = listOf(
			observation(
				oracle = oracle(CertificationReviewVerdict.SUPPORTED, setOf(evidenceId)),
				initial = review(CertificationReviewVerdict.SUPPORTED, setOf(UUID.fromString("10000000-0000-0000-0000-000000000099"))),
			),
			observation(
				oracle = oracle(CertificationReviewVerdict.NEEDS_SUPPORT),
				initial = review(CertificationReviewVerdict.SUPPORTED, setOf(evidenceId)),
			),
			observation(
				oracle = oracle(CertificationReviewVerdict.CONFLICT, setOf(evidenceId)),
				initial = review(CertificationReviewVerdict.SUPPORTED, setOf(evidenceId)),
			),
		)

		val grades = cases.map(evaluator::evaluate)

		assertTrue(grades.all { it.outcome == EvidenceOutcome.HARD_GATE_FAIL })
		assertTrue(HardGateCode.UNKNOWN_CITATION_ID in grades[0].codes)
		assertTrue(HardGateCode.UNSUPPORTED_FACT_CONFIRMED in grades[1].codes)
		assertTrue(HardGateCode.CONFLICT_NOT_DETECTED in grades[2].codes)
	}

	@Test
	fun `non factual prose passes without citations and rewritten text is rescored`() {
		val evaluator = CertificationCaseEvaluator()
		val nonFactual = evaluator.evaluate(
			observation(
				oracle = oracle(CertificationReviewVerdict.NOT_REQUIRED),
				initial = review(CertificationReviewVerdict.NOT_REQUIRED),
			),
		)
		val staleRewrite = evaluator.evaluate(
			observation(
				oracle = oracle(
					CertificationReviewVerdict.NEEDS_SUPPORT,
					rewriteExpectedVerdict = CertificationReviewVerdict.SUPPORTED,
					rewriteExpectedEvidenceIds = setOf(evidenceId),
				),
				initial = review(CertificationReviewVerdict.NEEDS_SUPPORT),
				rewritten = review(CertificationReviewVerdict.NEEDS_SUPPORT),
			),
		)

		assertEquals(EvidenceOutcome.PASS, nonFactual.outcome)
		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, staleRewrite.outcome)
		assertTrue(HardGateCode.REWRITE_RESULT_FAILED in staleRewrite.codes)
		assertFalse(HardGateCode.UNSUPPORTED_FACT_CONFIRMED in staleRewrite.codes)
	}

	@Test
	fun `schema route injected urls and changed evidence links fail deterministically`() {
		val grade = CertificationCaseEvaluator().evaluate(
			observation(
				oracle = oracle(CertificationReviewVerdict.SUPPORTED, setOf(evidenceId)),
				initial = review(CertificationReviewVerdict.SUPPORTED, setOf(evidenceId)),
			).copy(
				writerSchemaValid = false,
				routeAttributionValid = false,
				linksPreserved = false,
				modelSuppliedUrlCount = 1,
			),
		)

		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, grade.outcome)
		assertEquals(
			setOf(
				HardGateCode.SCHEMA_INVALID,
				HardGateCode.ROUTE_ATTRIBUTION_INVALID,
				HardGateCode.LINK_PRESERVATION_FAILED,
				HardGateCode.MODEL_SUPPLIED_URL,
			),
			grade.codes,
		)
	}

	@Test
	fun `reviewing only oracle prose cannot substitute for the writer review composition`() {
		val grade = CertificationCaseEvaluator().evaluate(
			observation(
				oracle = oracle(CertificationReviewVerdict.SUPPORTED, setOf(evidenceId)),
				initial = review(CertificationReviewVerdict.SUPPORTED, setOf(evidenceId)),
			).copy(writerReviewCompositionValid = false),
		)

		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, grade.outcome)
		assertTrue(HardGateCode.WRITER_REVIEW_COMPOSITION_INVALID in grade.codes)
	}

	private fun oracle(
		expectedVerdict: CertificationReviewVerdict,
		expectedEvidenceIds: Set<UUID> = emptySet(),
		rewriteExpectedVerdict: CertificationReviewVerdict? = null,
		rewriteExpectedEvidenceIds: Set<UUID> = emptySet(),
	) = CertificationSentenceOracle(
		sentenceId = sentenceId,
		expectedVerdict = expectedVerdict,
		expectedEvidenceIds = expectedEvidenceIds,
		rewriteExpectedVerdict = rewriteExpectedVerdict,
		rewriteExpectedEvidenceIds = rewriteExpectedEvidenceIds,
	)

	private fun review(verdict: CertificationReviewVerdict, evidenceIds: Set<UUID> = emptySet()) =
		CertificationReviewObservation(sentenceId, verdict, evidenceIds)

	private fun observation(
		oracle: CertificationSentenceOracle,
		initial: CertificationReviewObservation,
		rewritten: CertificationReviewObservation? = null,
	) = CertificationCaseObservation(
		scenarioId = "safe-scenario",
		knownEvidenceIds = setOf(evidenceId),
		sentenceOracles = listOf(oracle),
		initialReviews = listOf(initial),
		rewrittenReviews = rewritten?.let(::listOf),
	)
}
