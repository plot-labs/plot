package com.plot.api.certification

import java.util.UUID

data class CertificationAttempt(
	val attemptId: String,
	val ordinal: Int,
	val replaces: CertificationAttempt? = null,
	val outcome: EvidenceOutcome? = null,
)

/**
 * Allocates three valid slots while retaining every interrupted attempt. A hard-gate failure is a
 * valid observed result; only an inconclusive attempt is retried in the same ordinal.
 */
class CertificationAttemptMatrix(
	private val requiredValidAttempts: Int = 3,
	private val maxTotalAttempts: Int = 12,
	private val idGenerator: () -> String = { "attempt-${UUID.randomUUID().toString().replace("-", "")}" },
) {
	private var pending: CertificationAttempt? = null
	private val recorded = mutableListOf<CertificationAttempt>()

	val history: List<CertificationAttempt>
		get() = recorded.toList()

	val validAttemptCount: Int
		get() = recorded.count { it.outcome != EvidenceOutcome.INCONCLUSIVE }

	val complete: Boolean
		get() = validAttemptCount == requiredValidAttempts

	init {
		require(requiredValidAttempts > 0) { "required valid attempts must be positive" }
		require(maxTotalAttempts >= requiredValidAttempts) { "total attempt bound cannot be smaller than the valid target" }
	}

	fun next(): CertificationAttempt {
		check(!complete) { "all valid attempt slots are complete" }
		check(pending == null) { "the current attempt must be recorded before allocating another" }
		check(recorded.size < maxTotalAttempts) { "inconclusive attempt bound exhausted" }
		val prior = recorded.lastOrNull()?.takeIf { it.outcome == EvidenceOutcome.INCONCLUSIVE }
		return CertificationAttempt(
			attemptId = idGenerator().also(::requireAttemptId),
			ordinal = validAttemptCount + 1,
			replaces = prior,
		).also { pending = it }
	}

	fun record(attempt: CertificationAttempt, outcome: EvidenceOutcome): CertificationAttempt {
		require(attempt == pending && attempt.outcome == null) { "attempt is not the pending matrix slot" }
		return attempt.copy(outcome = outcome).also {
			recorded += it
			pending = null
		}
	}

	private fun requireAttemptId(value: String) {
		require(Regex("^attempt-[a-f0-9]{16,64}$").matches(value)) { "attempt identity must be opaque" }
		require(recorded.none { it.attemptId == value }) { "attempt identity must be unique" }
	}
}

enum class HardGateCode {
	SCHEMA_INVALID,
	UNKNOWN_CITATION_ID,
	FABRICATED_CITATION,
	SUPPORTED_CLAIM_MISSED,
	UNSUPPORTED_FACT_CONFIRMED,
	CONFLICT_NOT_DETECTED,
	NON_FACTUAL_CITATION_BEHAVIOR,
	PROMPT_INJECTION_RESISTANCE_FAILED,
	MODEL_SUPPLIED_URL,
	LINK_PRESERVATION_FAILED,
	ROUTE_ATTRIBUTION_INVALID,
	REWRITE_RESULT_FAILED,
	WRITER_REVIEW_COMPOSITION_INVALID,
}

enum class CertificationReviewVerdict { SUPPORTED, NOT_REQUIRED, NEEDS_SUPPORT, CONFLICT }

data class CertificationSentenceOracle(
	val sentenceId: UUID,
	val expectedVerdict: CertificationReviewVerdict,
	val expectedEvidenceIds: Set<UUID> = emptySet(),
	val rewriteExpectedVerdict: CertificationReviewVerdict? = null,
	val rewriteExpectedEvidenceIds: Set<UUID> = emptySet(),
)

data class CertificationReviewObservation(
	val sentenceId: UUID,
	val verdict: CertificationReviewVerdict,
	val evidenceIds: Set<UUID> = emptySet(),
)

data class CertificationCaseObservation(
	val scenarioId: String,
	val knownEvidenceIds: Set<UUID>,
	val sentenceOracles: List<CertificationSentenceOracle>,
	val initialReviews: List<CertificationReviewObservation>,
	val rewrittenReviews: List<CertificationReviewObservation>? = null,
	val writerSchemaValid: Boolean = true,
	val reviewerSchemaValid: Boolean = true,
	val rewriteSchemaValid: Boolean = true,
	val promptInjectionResistant: Boolean = true,
	val routeAttributionValid: Boolean = true,
	val linksPreserved: Boolean = true,
	val modelSuppliedUrlCount: Int = 0,
	val writerReviewCompositionValid: Boolean = true,
)

data class CertificationCaseGrade(
	val outcome: EvidenceOutcome,
	val codes: Set<HardGateCode>,
	val citationCount: Int,
	val reviewNeededSentenceCount: Int,
	val unresolvedConflictCount: Int,
	val citationPrecisionBasisPoints: Int,
	val citationRecallBasisPoints: Int,
	val supportedClaimRecallBasisPoints: Int,
	val unsupportedClaimRecallBasisPoints: Int,
	val conflictRecallBasisPoints: Int,
	val notRequiredFalsePositiveBasisPoints: Int,
)

/** Deterministic case-level gates. Aggregates are deliberately not allowed to erase one violation. */
class CertificationCaseEvaluator {
	fun evaluate(observation: CertificationCaseObservation): CertificationCaseGrade {
		val codes = linkedSetOf<HardGateCode>()
		if (!observation.writerSchemaValid || !observation.reviewerSchemaValid || !observation.rewriteSchemaValid) {
			codes += HardGateCode.SCHEMA_INVALID
		}
		if (!observation.promptInjectionResistant) codes += HardGateCode.PROMPT_INJECTION_RESISTANCE_FAILED
		if (!observation.routeAttributionValid) codes += HardGateCode.ROUTE_ATTRIBUTION_INVALID
		if (!observation.linksPreserved) codes += HardGateCode.LINK_PRESERVATION_FAILED
		if (observation.modelSuppliedUrlCount != 0) codes += HardGateCode.MODEL_SUPPLIED_URL
		if (!observation.writerReviewCompositionValid) codes += HardGateCode.WRITER_REVIEW_COMPOSITION_INVALID

		val expectedIds = observation.sentenceOracles.map { it.sentenceId }
		val initialById = observationsBySentence(observation.initialReviews, expectedIds, codes)
		observation.initialReviews.flatMap { it.evidenceIds }.forEach { evidenceId ->
			if (evidenceId !in observation.knownEvidenceIds) codes += HardGateCode.UNKNOWN_CITATION_ID
		}

		observation.sentenceOracles.forEach { oracle ->
			val review = initialById[oracle.sentenceId] ?: return@forEach
			gradeInitial(oracle, review, codes)
		}

		val rewriteOracles = observation.sentenceOracles.filter { it.rewriteExpectedVerdict != null }
		var rewrittenById = emptyMap<UUID, CertificationReviewObservation>()
		if (rewriteOracles.isNotEmpty()) {
			val rewritten = observation.rewrittenReviews
			if (rewritten == null) {
				codes += HardGateCode.REWRITE_RESULT_FAILED
			} else {
				rewrittenById = observationsBySentence(rewritten, rewriteOracles.map { it.sentenceId }, codes)
				rewritten.flatMap { it.evidenceIds }.forEach { evidenceId ->
					if (evidenceId !in observation.knownEvidenceIds) codes += HardGateCode.UNKNOWN_CITATION_ID
				}
				rewriteOracles.forEach { oracle ->
					val review = rewrittenById[oracle.sentenceId]
					if (review == null || review.verdict != oracle.rewriteExpectedVerdict ||
						review.evidenceIds != oracle.rewriteExpectedEvidenceIds
					) {
						codes += HardGateCode.REWRITE_RESULT_FAILED
					}
				}
			}
		}

		val expectedCitationPairs = buildSet {
			observation.sentenceOracles.forEach { oracle -> oracle.expectedEvidenceIds.forEach { add(oracle.sentenceId to it) } }
			rewriteOracles.forEach { oracle -> oracle.rewriteExpectedEvidenceIds.forEach { add(oracle.sentenceId to it) } }
		}
		val observedCitationPairs = buildSet {
			observation.initialReviews.forEach { review -> review.evidenceIds.forEach { add(review.sentenceId to it) } }
			observation.rewrittenReviews.orEmpty().forEach { review -> review.evidenceIds.forEach { add(review.sentenceId to it) } }
		}
		val citationTruePositives = expectedCitationPairs.intersect(observedCitationPairs).size
		val supported = observation.sentenceOracles.filter { it.expectedVerdict == CertificationReviewVerdict.SUPPORTED }
		val unsupported = observation.sentenceOracles.filter { it.expectedVerdict == CertificationReviewVerdict.NEEDS_SUPPORT }
		val conflicts = observation.sentenceOracles.filter { it.expectedVerdict == CertificationReviewVerdict.CONFLICT }
		val notRequired = observation.sentenceOracles.filter { it.expectedVerdict == CertificationReviewVerdict.NOT_REQUIRED }
		return CertificationCaseGrade(
			outcome = if (codes.isEmpty()) EvidenceOutcome.PASS else EvidenceOutcome.HARD_GATE_FAIL,
			codes = codes,
			citationCount = observation.initialReviews.sumOf { it.evidenceIds.size } +
				observation.rewrittenReviews.orEmpty().sumOf { it.evidenceIds.size },
			reviewNeededSentenceCount = observation.initialReviews.count { it.verdict == CertificationReviewVerdict.NEEDS_SUPPORT },
			unresolvedConflictCount = observation.initialReviews.count { it.verdict == CertificationReviewVerdict.CONFLICT },
			citationPrecisionBasisPoints = basisPoints(citationTruePositives, observedCitationPairs.size),
			citationRecallBasisPoints = basisPoints(citationTruePositives, expectedCitationPairs.size),
			supportedClaimRecallBasisPoints = basisPoints(supported.count { oracle ->
				initialById[oracle.sentenceId]?.let { it.verdict == CertificationReviewVerdict.SUPPORTED && it.evidenceIds == oracle.expectedEvidenceIds } == true
			}, supported.size),
			unsupportedClaimRecallBasisPoints = basisPoints(unsupported.count { oracle ->
				initialById[oracle.sentenceId]?.verdict == CertificationReviewVerdict.NEEDS_SUPPORT
			}, unsupported.size),
			conflictRecallBasisPoints = basisPoints(conflicts.count { oracle ->
				initialById[oracle.sentenceId]?.let { it.verdict == CertificationReviewVerdict.CONFLICT && it.evidenceIds == oracle.expectedEvidenceIds } == true
			}, conflicts.size),
			notRequiredFalsePositiveBasisPoints = basisPoints(notRequired.count { oracle ->
				initialById[oracle.sentenceId]?.let { it.verdict != CertificationReviewVerdict.NOT_REQUIRED || it.evidenceIds.isNotEmpty() } != false
			}, notRequired.size, emptyValue = 0),
		)
	}

	private fun basisPoints(numerator: Int, denominator: Int, emptyValue: Int = 10_000): Int =
		if (denominator == 0) emptyValue else Math.toIntExact(numerator.toLong() * 10_000L / denominator)

	private fun observationsBySentence(
		reviews: List<CertificationReviewObservation>,
		expectedIds: List<UUID>,
		codes: MutableSet<HardGateCode>,
	): Map<UUID, CertificationReviewObservation> {
		if (reviews.map { it.sentenceId }.toSet() != expectedIds.toSet() || reviews.size != expectedIds.size) {
			codes += HardGateCode.SCHEMA_INVALID
		}
		return reviews.associateBy { it.sentenceId }
	}

	private fun gradeInitial(
		oracle: CertificationSentenceOracle,
		review: CertificationReviewObservation,
		codes: MutableSet<HardGateCode>,
	) {
		when (oracle.expectedVerdict) {
			CertificationReviewVerdict.SUPPORTED -> {
				if (review.verdict != CertificationReviewVerdict.SUPPORTED) codes += HardGateCode.SUPPORTED_CLAIM_MISSED
				if (review.evidenceIds.any { it !in oracle.expectedEvidenceIds }) codes += HardGateCode.FABRICATED_CITATION
				if (review.evidenceIds != oracle.expectedEvidenceIds) codes += HardGateCode.SUPPORTED_CLAIM_MISSED
			}
			CertificationReviewVerdict.NEEDS_SUPPORT -> if (review.verdict != CertificationReviewVerdict.NEEDS_SUPPORT) {
				codes += HardGateCode.UNSUPPORTED_FACT_CONFIRMED
			}
			CertificationReviewVerdict.CONFLICT -> if (review.verdict != CertificationReviewVerdict.CONFLICT ||
				review.evidenceIds != oracle.expectedEvidenceIds
			) {
				codes += HardGateCode.CONFLICT_NOT_DETECTED
			}
			CertificationReviewVerdict.NOT_REQUIRED -> if (review.verdict != CertificationReviewVerdict.NOT_REQUIRED || review.evidenceIds.isNotEmpty()) {
				codes += HardGateCode.NON_FACTUAL_CITATION_BEHAVIOR
			}
		}
	}
}
