package com.plot.api.certification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CertificationEvidenceReconcilerTest {
	private val reconciler = CertificationEvidenceReconciler()

	@Test
	fun `pending browser observation becomes pass only after matching durable audit`() {
		val result = reconciler.reconcile(campaign(), execution(), browser(), audit())

		assertEquals(EvidenceOutcome.PASS, result.outcome)
		assertEquals(null, result.replacesAttemptId)
		assertEquals(listOf(ReconciliationCode.RECONCILED), result.codes)
		assertEquals(2, result.durableExportEventCount)
		assertEquals(CAMPAIGN_HASH, result.campaignManifestHash)
		assertEquals(EXECUTION_HASH, result.modelExecutionManifestHash)
		assertEquals(SOURCE_HASH, result.sourceSnapshotSetHash)
	}

	@Test
	fun `bounded conflict rewrite review loops remain valid durable evidence`() {
		val roles = listOf("WRITER", "REVIEWER", "REWRITER", "REVIEWER", "REWRITER", "REVIEWER")
		val responseHashes = listOf('1', '2', '3', '4', '5', '6')
		val metadataHashes = listOf('7', '8', '9', 'a', 'b', 'c')
		val invocations = roles.mapIndexed { index, role ->
			CertificationInvocationAudit(
				hash(responseHashes[index]), role, index, "SUCCEEDED", hash(metadataHashes[index]), 10, 10,
			)
		}
		val expandedAudit = audit().copy(
			transitionVersion = 10,
			invocations = invocations,
			artifactTypes = listOf(
				"EVIDENCE_SET", "WRITER_OUTPUT", "REVIEWER_OUTPUT", "CONFLICT_DECISION",
				"REWRITER_OUTPUT", "REVIEWER_OUTPUT", "CONFLICT_DECISION", "REWRITER_OUTPUT",
				"REVIEWER_OUTPUT", "FINAL_OUTPUT",
			),
			interventionCount = 2,
			resolvedInterventionCount = 2,
			rewriteCount = 2,
			invocationAttributions = roles.mapIndexed { index, role -> attribution(index, role, metadataHashes[index]) },
		)

		val result = reconciler.reconcile(campaign(), execution(), browser(), expandedAudit)

		assertEquals(EvidenceOutcome.PASS, result.outcome)
		assertEquals(listOf(ReconciliationCode.RECONCILED), result.codes)
		assertEquals(2, result.durableInterventionCount)
	}

	@Test
	fun `reconciliation preserves the shared model attempt replacement lineage`() {
		val priorAttemptId = "attempt-bbbbbbbbbbbbbbbb"

		val result = reconciler.reconcile(campaign(), execution(), browser(), audit(), priorAttemptId)

		assertEquals(EvidenceOutcome.PASS, result.outcome)
		assertEquals(priorAttemptId, result.replacesAttemptId)
		assertEquals(listOf(ReconciliationCode.RECONCILED), result.codes)
	}

	@Test
	fun `reconciliation rejects malformed or self-referential attempt lineage`() {
		val malformed = reconciler.reconcile(campaign(), execution(), browser(), audit(), "attempt-short")
		val self = reconciler.reconcile(campaign(), execution(), browser(), audit(), ATTEMPT)

		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, malformed.outcome)
		assertTrue(ReconciliationCode.IDENTITY_MISMATCH in malformed.codes)
		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, self.outcome)
		assertTrue(ReconciliationCode.IDENTITY_MISMATCH in self.codes)
	}

	@Test
	fun `infrastructure observation remains inconclusive without semantic mismatch noise`() {
		val browser = browser().copy(
			outcome = EvidenceOutcome.INCONCLUSIVE,
			codes = listOf("BROWSER_INFRASTRUCTURE_INCONCLUSIVE"),
			metrics = mapOf("latencyMs" to 1),
		)
		val result = reconciler.reconcile(campaign(), execution(), browser, audit().copy(exports = emptyList()))

		assertEquals(EvidenceOutcome.INCONCLUSIVE, result.outcome)
		assertEquals(listOf(ReconciliationCode.BROWSER_INFRASTRUCTURE_INCONCLUSIVE), result.codes)
	}

	@Test
	fun `identity and manifest mixing are hard failures even for infrastructure observations`() {
		val result = reconciler.reconcile(
			campaign(),
			execution(),
			browser().copy(campaignManifestHash = hash('9'), codes = listOf("BROWSER_INFRASTRUCTURE_INCONCLUSIVE")),
			audit(),
		)

		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, result.outcome)
		assertTrue(ReconciliationCode.IDENTITY_MISMATCH in result.codes)
	}

	@Test
	fun `incomplete browser claims cannot pass reconciliation`() {
		val result = reconciler.reconcile(
			campaign(), execution(), browser().copy(codes = browser().codes - "STALE_EDIT_OBSERVED"), audit(),
		)

		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, result.outcome)
		assertTrue(ReconciliationCode.BROWSER_OBSERVATION_NOT_PENDING_AUDIT in result.codes)
	}

	@Test
	fun `sentence edit evidence-free and citation states are independently reconciled`() {
		val sentenceMismatch = reconciler.reconcile(
			campaign(), execution(), browser(),
			audit().copy(currentRevisionOriginCounts = mapOf("GENERATED" to 3), sentenceVerdictCounts = mapOf("SUPPORTED" to 2)),
		)
		val citationMismatch = reconciler.reconcile(
			campaign(), execution(), browser(), audit().copy(citationStatusCounts = mapOf("ACTIVE" to 2)),
		)

		assertTrue(ReconciliationCode.SENTENCE_STATE_MISMATCH in sentenceMismatch.codes)
		assertTrue(ReconciliationCode.CITATION_STATE_MISMATCH in citationMismatch.codes)
	}

	@Test
	fun `citation and warning export mismatches are hard failures`() {
		val result = reconciler.reconcile(
			campaign(), execution(), browser(),
			audit().copy(citationCount = 1, exports = audit().exports.take(1)),
		)

		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, result.outcome)
		assertTrue(ReconciliationCode.CITATION_COUNT_MISMATCH in result.codes)
		assertTrue(ReconciliationCode.EXPORT_SEQUENCE_MISMATCH in result.codes)
	}

	@Test
	fun `missing or mismatched invocation attribution cannot pass`() {
		val missing = reconciler.reconcile(campaign(), execution(), browser(), audit().copy(invocationAttributions = emptyList()))
		val mismatch = reconciler.reconcile(
			campaign(), execution(), browser(), audit().copy(invocationAttributions =
				audit().invocationAttributions.map { it.copy(servedModel = "openai/other-2026-01-01") }),
		)

		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, missing.outcome)
		assertTrue(ReconciliationCode.ROUTE_ATTRIBUTION_MISMATCH in missing.codes)
		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, mismatch.outcome)
		assertTrue(ReconciliationCode.ROUTE_ATTRIBUTION_MISMATCH in mismatch.codes)
	}

	@Test
	fun `provider metadata delay remains inconclusive`() {
		val result = reconciler.reconcile(
			campaign(), execution(), browser(), audit().copy(invocationAttributions =
				audit().invocationAttributions.map { it.copy(
					outcome = EvidenceOutcome.INCONCLUSIVE,
					code = CertificationFailureCode.METADATA_PENDING,
					gateway = null, requestedModel = null, servedModel = null, observedUpstream = null,
					responseIdHash = null, upstreamIdHash = null,
				) }),
		)

		assertEquals(EvidenceOutcome.INCONCLUSIVE, result.outcome)
		assertEquals(listOf(ReconciliationCode.ROUTE_ATTRIBUTION_INCONCLUSIVE), result.codes)
	}

	private fun campaign() = SealedArtifact(
		CampaignManifest(
			"artifact-aaaaaaaaaaaaaaaa", CAMPAIGN, "2026-07-16T00:00:00Z", "a".repeat(40), hash('1'), hash('2'),
			SOURCE_HASH, hash('3'), "report-aaaaaaaaaaaaaaaa", listOf("source-aaaaaaaaaaaaaaaa"), null,
		),
		CAMPAIGN_HASH,
	)

	private fun execution() = SealedArtifact(
		ModelExecutionManifest(
			"artifact-bbbbbbbbbbbbbbbb", CAMPAIGN, CAMPAIGN_HASH, EXECUTION, "2026-07-16T00:00:00Z",
			"openai/gpt-5.4-nano", "openai/gpt-5.4-nano-2026-06-01", hash('4'), "openai",
			hash('5'), "process-aaaaaaaaaaaaaaaa", "namespace-aaaaaaaaaaaaaaaa", "namespace-bbbbbbbbbbbbbbbb",
			listOf(SCENARIO),
		),
		EXECUTION_HASH,
	)

	private fun browser() = EvidenceEnvelope(
		"artifact-cccccccccccccccc", CAMPAIGN, CAMPAIGN_HASH, EXECUTION, EXECUTION_HASH, "2026-07-16T00:00:00Z",
		EvidenceType.BROWSER_OBSERVATION, EvidenceSubjectType.ATTEMPT, ATTEMPT, SCENARIO, 1, EvidenceOutcome.INCONCLUSIVE,
		mapOf("latencyMs" to 100, "citationCount" to 2, "reviewNeededSentenceCount" to 1, "exportEventCount" to 2),
		listOf(
			"BROWSER_CONTRACT_OBSERVED", "CITATION_POPOVER_OBSERVED", "EVIDENCE_FREE_SENTENCE_OBSERVED",
			"EXPORT_CONFIRMATION_OBSERVED", "MARKDOWN_SAFETY_OBSERVED",
			"PENDING_AUDIT_RECONCILIATION", "REAL_GITHUB_BLOCKS_OBSERVED", "STALE_EDIT_OBSERVED",
		),
		null, null,
	)

	private fun audit() = CertificationAuditEnvelope(
		campaignId = CAMPAIGN,
		campaignManifestHash = CAMPAIGN_HASH,
		modelExecutionId = EXECUTION,
		modelExecutionManifestHash = EXECUTION_HASH,
		attemptId = ATTEMPT,
		scenarioId = SCENARIO,
		ordinal = 1,
		recordedAt = "2026-07-16T00:00:01Z",
		runStatus = "READY",
		transitionVersion = 4,
		sourceSnapshotSetHash = SOURCE_HASH,
		invocations = listOf(
			CertificationInvocationAudit(hash('7'), "WRITER", 0, "SUCCEEDED", hash('8'), 10, 10),
			CertificationInvocationAudit(hash('9'), "REVIEWER", 1, "SUCCEEDED", hash('a'), 10, 10),
		),
		artifactTypes = listOf(
			"EVIDENCE_SET", "WRITER_OUTPUT", "REVIEWER_OUTPUT", "CONFLICT_DECISION", "FINAL_OUTPUT",
		),
		sentenceVerdictCounts = mapOf("SUPPORTED" to 2, "NOT_REQUIRED" to 1),
		currentRevisionOriginCounts = mapOf("GENERATED" to 2, "USER_MODIFIED" to 1),
		citationStatusCounts = mapOf("ACTIVE" to 1, "STALE" to 1),
		citationCount = 2,
		interventionCount = 1,
		resolvedInterventionCount = 1,
		rewriteCount = 0,
		exports = listOf(
			CertificationExportAudit(hash('b'), "COPY", "REJECTED", 1, false, null),
			CertificationExportAudit(hash('c'), "COPY", "SUCCEEDED", 1, true, hash('d')),
		),
		invocationAttributions = listOf(
			attribution(0, "WRITER", '8'),
			attribution(1, "REVIEWER", 'a'),
		),
	)

	private fun attribution(index: Int, role: String, hashValue: Char) = CertificationInvocationAttributionAudit(
		logicalCallIndex = index,
		role = role,
		outcome = EvidenceOutcome.PASS,
		code = null,
		gateway = "openrouter",
		requestedModel = "openai/gpt-5.4-nano",
		servedModel = "openai/gpt-5.4-nano-2026-06-01",
		observedUpstream = "openai",
		promptTokens = 5,
		completionTokens = 5,
		reasoningTokens = 0,
		cachedTokens = 0,
		costUsdMicros = 1,
		latencyMs = 10,
		responseIdHash = hash(hashValue),
		upstreamIdHash = hash('e'),
	)

	private fun hash(character: Char) = "sha256:${character.toString().repeat(64)}"

	companion object {
		private const val CAMPAIGN = "campaign-aaaaaaaaaaaaaaaa"
		private const val EXECUTION = "model-execution-aaaaaaaaaaaaaaaa"
		private const val ATTEMPT = "attempt-aaaaaaaaaaaaaaaa"
		private const val SCENARIO = "real-github-journey"
		private const val CAMPAIGN_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
		private const val EXECUTION_HASH = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
		private const val SOURCE_HASH = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
	}
}
