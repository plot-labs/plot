package com.plot.api.certification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CertificationReportRendererTest {
	private val renderer = CertificationReportRenderer()

	@Test
	fun `final report chooses the cost-first passing model and retains failed alternate evidence`() {
		val report = renderer.render(input(
			selected = NANO_EXECUTION,
			nanoOutcomes = listOf(EvidenceOutcome.PASS, EvidenceOutcome.PASS, EvidenceOutcome.PASS),
			miniOutcomes = listOf(EvidenceOutcome.PASS, EvidenceOutcome.HARD_GATE_FAIL, EvidenceOutcome.PASS),
		))

		assertTrue(report.computedEligible)
		assertEquals(OperatorDecision.GO, report.finalDecision)
		assertTrue(report.markdown.contains("HARD_GATE_FAIL"))
		assertTrue(report.markdown.contains("UNSUPPORTED_FACT_CONFIRMED"))
		assertFalse(report.markdown.contains(hash('d')))
		assertFalse(report.markdown.contains("outputContentHash"))
	}

	@Test
	fun `4o mini is eligible only as fallback after nano fails`() {
		val report = renderer.render(input(
			selected = MINI_EXECUTION,
			nanoOutcomes = listOf(EvidenceOutcome.PASS, EvidenceOutcome.HARD_GATE_FAIL, EvidenceOutcome.PASS),
			miniOutcomes = listOf(EvidenceOutcome.PASS, EvidenceOutcome.PASS, EvidenceOutcome.PASS),
		))

		assertTrue(report.computedEligible)
		assertEquals(OperatorDecision.GO, report.finalDecision)
	}

	@Test
	fun `no passing model cannot certify GO through a null selection`() {
		assertFailsWith<CertificationReportException> {
			renderer.render(input(
				selected = null,
				nanoOutcomes = List(3) { EvidenceOutcome.HARD_GATE_FAIL },
				miniOutcomes = List(3) { EvidenceOutcome.HARD_GATE_FAIL },
			))
		}
	}

	@Test
	fun `post-purge cleanup finalizes only the redacted draft snapshot`() {
		val draft = input(
			selected = NANO_EXECUTION,
			nanoOutcomes = List(3) { EvidenceOutcome.PASS },
			miniOutcomes = List(3) { EvidenceOutcome.PASS },
		).copy(phase = ReportPhase.DRAFT)
		val finalized = CertificationReportSnapshotFinalizer.finalize(draft, draft.cleanup, draft.operator)

		assertEquals(ReportPhase.FINAL, finalized.phase)
		assertEquals(EvidenceOutcome.PASS, finalized.cleanup.outcome)
	}

	@Test
	fun `operator cannot select the expensive model when nano passes`() {
		assertFailsWith<CertificationReportException> {
			renderer.render(input(
				selected = MINI_EXECUTION,
				nanoOutcomes = List(3) { EvidenceOutcome.PASS },
				miniOutcomes = List(3) { EvidenceOutcome.PASS },
			))
		}
	}

	@Test
	fun `cleanup failure prevents final GO`() {
		val unsafe = input(
			selected = NANO_EXECUTION,
			nanoOutcomes = List(3) { EvidenceOutcome.PASS },
			miniOutcomes = List(3) { EvidenceOutcome.PASS },
		).copy(cleanup = CertificationCleanupGate().evaluate(
			cleanupObservation().copy(listenerCount = 1), java.time.Instant.parse("2026-07-16T00:01:00Z"),
		))

		assertFailsWith<CertificationReportException> { renderer.render(unsafe) }
	}

	@Test
	fun `browser results must join the exact model attempt scenario ordinal and lineage`() {
		val base = input(NANO_EXECUTION, List(3) { EvidenceOutcome.PASS }, List(3) { EvidenceOutcome.PASS })
		val injected = base.browserReconciliations.first().copy(attemptId = "attempt-9999999999999999")

		assertFailsWith<CertificationRedactionException> {
			renderer.render(base.copy(browserReconciliations = listOf(injected) + base.browserReconciliations.drop(1)))
		}
		val orphan = base.browserReconciliations.first().copy(replacesAttemptId = "attempt-9999999999999999")
		assertFailsWith<CertificationRedactionException> {
			renderer.render(base.copy(browserReconciliations = listOf(orphan) + base.browserReconciliations.drop(1)))
		}
	}

	@Test
	fun `inconclusive attempt remains visible and must terminate in a valid replacement`() {
		val inconclusive = attempt(1, EvidenceOutcome.INCONCLUSIVE, "attempt-1111111111111111")
		val replacement = attempt(1, EvidenceOutcome.PASS, "attempt-7777777777777777", inconclusive.attemptId)
		val nano = model(NANO_EXECUTION, "openai/gpt-5.4-nano", listOf(inconclusive, replacement, attempt(2), attempt(3)))
		val base = input(NANO_EXECUTION, List(3) { EvidenceOutcome.PASS }, List(3) { EvidenceOutcome.PASS })
		val browser = listOf(reconciliation(NANO_EXECUTION, 1, EvidenceOutcome.PASS, replacement.attemptId)) +
			base.browserReconciliations.filterNot { it.modelExecutionId == NANO_EXECUTION && it.ordinal == 1 }
		val report = renderer.render(base.copy(models = listOf(nano, base.models[1]), browserReconciliations = browser))

		assertTrue(report.markdown.contains(inconclusive.attemptId))
		assertTrue(report.markdown.contains(replacement.attemptId))
		assertTrue(report.computedEligible)

		val broken = nano.copy(attempts = nano.attempts.filterNot { it.attemptId == replacement.attemptId })
		assertFailsWith<CertificationRedactionException> {
			renderer.render(base.copy(models = listOf(broken, base.models[1]), browserReconciliations = browser))
		}
	}

	@Test
	fun `browser infrastructure replacement may use a fresh passing model attempt without model lineage`() {
		val original = attempt(1, EvidenceOutcome.PASS, "attempt-1111111111111111")
		val replacement = attempt(1, EvidenceOutcome.PASS, "attempt-7777777777777777")
		val nano = model(NANO_EXECUTION, "openai/gpt-5.4-nano", listOf(original, replacement, attempt(2), attempt(3)))
		val base = input(NANO_EXECUTION, List(3) { EvidenceOutcome.PASS }, List(3) { EvidenceOutcome.PASS })
		val browser = listOf(
			reconciliation(NANO_EXECUTION, 1, EvidenceOutcome.INCONCLUSIVE, original.attemptId),
			reconciliation(NANO_EXECUTION, 1, EvidenceOutcome.PASS, replacement.attemptId, original.attemptId),
		) + base.browserReconciliations.filterNot { it.modelExecutionId == NANO_EXECUTION && it.ordinal == 1 }

		val report = renderer.render(base.copy(models = listOf(nano, base.models[1]), browserReconciliations = browser))

		assertTrue(report.computedEligible)
		assertTrue(report.markdown.contains(ReconciliationCode.BROWSER_INFRASTRUCTURE_INCONCLUSIVE.name))
	}

	private fun input(
		selected: String?,
		nanoOutcomes: List<EvidenceOutcome>,
		miniOutcomes: List<EvidenceOutcome>,
	): CertificationReportInput {
		val nano = model(NANO_EXECUTION, "openai/gpt-5.4-nano", nanoOutcomes.mapIndexed { index, outcome ->
			attempt(index + 1, outcome, "attempt-${(index + 1).toString().repeat(16)}")
		})
		val mini = model(MINI_EXECUTION, "openai/gpt-4o-mini-2024-07-18", miniOutcomes.mapIndexed { index, outcome ->
			attempt(index + 1, outcome, "attempt-${(index + 4).toString().repeat(16)}")
		})
		return CertificationReportInput(
			phase = ReportPhase.FINAL,
			sourceRevision = "a".repeat(40),
			campaignId = CAMPAIGN,
			campaignManifestHash = hash('a'),
			environmentAlias = "env-aaaaaaaaaaaaaaaa",
			sourceAliases = listOf("source-aaaaaaaaaaaaaaaa"),
			sourceSnapshotSetHash = hash('d'),
			corpusHash = hash('b'),
			profileHash = hash('c'),
			selectedModelExecutionId = selected,
			models = listOf(nano, mini),
			deterministicOutcome = EvidenceOutcome.PASS,
			browserReconciliations = nanoOutcomes.mapIndexed { index, outcome -> reconciliation(NANO_EXECUTION, index + 1, outcome) } +
				miniOutcomes.mapIndexed { index, outcome -> reconciliation(MINI_EXECUTION, index + 1, outcome) },
			processRestart = CertificationProcessRestartResult(
				CAMPAIGN, hash('a'), selected ?: NANO_EXECUTION, if (selected != MINI_EXECUTION) hash('e') else hash('f'),
				"attempt-9999999999999999", EvidenceOutcome.PASS, ProcessRestartCode.PROCESS_RESTART_RECONCILED, RestartCheckpointArtifact.WRITER_OUTPUT,
				hash('d'), 2, 2, 1, 1, 2,
			),
			cleanup = CertificationCleanupGate().evaluate(cleanupObservation(), java.time.Instant.parse("2026-07-16T00:01:00Z")),
			operator = CertificationOperatorDecision(
				"operator-aaaaaaaaaaaaaaaa", "2026-07-16T00:00:00Z", OperatorDecision.GO,
				CertificationOperatorRubric(5, 4, 5, HedgingRating.APPROPRIATE),
			),
		)
	}

	private fun model(executionId: String, requestedModel: String, attempts: List<CertificationAttemptReport>) = CertificationModelReport(
		executionId, hash(if (executionId == NANO_EXECUTION) 'e' else 'f'), hash('1'), hash('2'), requestedModel,
		if (requestedModel.contains("5.4")) "openai/gpt-5.4-nano-2026-06-01" else "openai/gpt-4o-mini-2024-07-18",
		"openai", attempts,
	)

	private fun attempt(
		ordinal: Int,
		outcome: EvidenceOutcome = EvidenceOutcome.PASS,
		id: String = "attempt-${ordinal.toString().repeat(16)}",
		replaces: String? = null,
	): CertificationAttemptReport {
		val hardCodes = if (outcome == EvidenceOutcome.HARD_GATE_FAIL) listOf(HardGateCode.UNSUPPORTED_FACT_CONFIRMED) else emptyList()
		val infrastructureCodes = if (outcome == EvidenceOutcome.INCONCLUSIVE) listOf(CertificationFailureCode.PROVIDER_UNAVAILABLE) else emptyList()
		val metrics = CertificationAttemptMetrics(true, 10, 5, 1, 0, 100, 200, 0, 2, 10_000, 10_000, 10_000, 10_000, 10_000, 0)
		return CertificationAttemptReport(
			id, "contract-corpus", ordinal, outcome, replaces, hardCodes, infrastructureCodes, metrics,
			listOf(CertificationScenarioReport("supported-claim", outcome, hardCodes, infrastructureCodes, metrics)),
		)
	}

	private fun reconciliation(
		modelExecutionId: String,
		ordinal: Int,
		outcome: EvidenceOutcome,
		attemptId: String = "attempt-${if (modelExecutionId == NANO_EXECUTION) ordinal else ordinal + 3}".let { prefix ->
			prefix + prefix.last().toString().repeat(15)
		},
		replacesAttemptId: String? = null,
	) = CertificationReconciliationResult(
		campaignId = CAMPAIGN,
		campaignManifestHash = hash('a'),
		modelExecutionId = modelExecutionId,
		modelExecutionManifestHash = hash(if (modelExecutionId == NANO_EXECUTION) 'e' else 'f'),
		sourceSnapshotSetHash = hash('d'),
		attemptId = attemptId,
		scenarioId = "real-github-journey",
		ordinal = ordinal,
		replacesAttemptId = replacesAttemptId,
		outcome = outcome,
		codes = listOf(when (outcome) {
			EvidenceOutcome.PASS -> ReconciliationCode.RECONCILED
			EvidenceOutcome.HARD_GATE_FAIL -> ReconciliationCode.BROWSER_CONTRACT_FAILED
			EvidenceOutcome.INCONCLUSIVE -> ReconciliationCode.BROWSER_INFRASTRUCTURE_INCONCLUSIVE
		}),
		durableModelCallCount = 2,
		durableCitationCount = 2,
		durableInterventionCount = 1,
		durableExportEventCount = 2,
		replacementModelResultHash = replacesAttemptId?.let { hash('9') },
	)

	private fun cleanupObservation() = CertificationCleanupObservation(
		campaignId = CAMPAIGN,
		campaignManifestHash = hash('a'),
		sourceRevision = "a".repeat(40),
		recordedAt = "2026-07-16T00:00:00Z",
		attestedByOperatorAlias = "operator-aaaaaaaaaaaaaaaa",
		attestedAt = "2026-07-16T00:00:00Z",
		listenerCount = 0,
		githubCredentialRevoked = true,
		openRouterCredentialRevoked = true,
		stateSecretDisposed = true,
		rawArtifactsDeleted = true,
		browserArtifactsDeleted = true,
		databaseDisposition = DatabaseDisposition.DESTROYED,
	)

	private fun hash(value: Char) = "sha256:${value.toString().repeat(64)}"

	companion object {
		private const val CAMPAIGN = "campaign-aaaaaaaaaaaaaaaa"
		private const val NANO_EXECUTION = "model-execution-aaaaaaaaaaaaaaaa"
		private const val MINI_EXECUTION = "model-execution-bbbbbbbbbbbbbbbb"
	}
}
