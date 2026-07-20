package com.plot.api.certification

import kotlin.test.Test
import kotlin.test.assertEquals

class CertificationProcessRestartReconcilerTest {
	private val reconciler = CertificationProcessRestartReconciler()

	@Test
	fun `durable writer checkpoint resumes exactly once after process restart`() {
		val result = reconciler.reconcile(before(), after())

		assertEquals(EvidenceOutcome.PASS, result.outcome)
		assertEquals(2, result.modelCallCount)
		assertEquals(1, result.contentPackCount)
	}

	@Test
	fun `identity drift or duplicate durable effects fail restart reconciliation`() {
		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, reconciler.reconcile(before(), after().copy(sourceSnapshotSetHash = hash('9'))).outcome)
		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, reconciler.reconcile(before(), after().copy(runIdHash = hash('9'))).outcome)
		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, reconciler.reconcile(before(), after().copy(writerSucceededCount = 2)).outcome)
		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, reconciler.reconcile(before(), after().copy(contentPackCount = 2)).outcome)
		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, reconciler.reconcile(
			before(), after().copy(uniqueness = after().uniqueness.copy(citationDistinctKeys = 1)),
		).outcome)
		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, reconciler.reconcile(
			before(), after().copy(uniqueness = after().uniqueness.copy(modelInvocationRows = 3, modelInvocationDistinctKeys = 3)),
		).outcome)
	}

	private fun before() = state("REVIEWING", 1, 0, 0, 0, 0, 0, 0)
	private fun after() = state("READY", 1, 1, 0, 2, 0, 1, 0)

	private fun state(
		status: String,
		writer: Int,
		reviewer: Int,
		rewriter: Int,
		citations: Int,
		interventions: Int,
		packs: Int,
		exports: Int,
	) = CertificationRestartDurableState(
		campaignId = "campaign-aaaaaaaaaaaaaaaa",
		campaignManifestHash = hash('a'),
		modelExecutionId = "model-execution-aaaaaaaaaaaaaaaa",
		modelExecutionManifestHash = hash('b'),
		attemptId = "attempt-aaaaaaaaaaaaaaaa",
		sourceSnapshotSetHash = hash('c'),
		workspaceIdHash = hash('d'),
		runIdHash = hash('e'),
		createdByUserIdHash = hash('f'),
		idempotencyKeyHash = hash('1'),
		requestFingerprintHash = hash('2'),
		provider = "OPENROUTER",
		modelName = "openai/gpt-5.4-nano",
		checkpointArtifact = RestartCheckpointArtifact.WRITER_OUTPUT,
		checkpointArtifactCount = 1,
		runStatus = status,
		writerSucceededCount = writer,
		reviewerSucceededCount = reviewer,
		rewriterSucceededCount = rewriter,
		citationCount = citations,
		interventionCount = interventions,
		contentPackCount = packs,
		exportEventCount = exports,
		uniqueness = CertificationRestartDistinctKeys(
			sourceInputRows = 1,
			sourceInputDistinctKeys = 1,
			workflowStepRows = writer + reviewer + rewriter,
			workflowStepDistinctKeys = writer + reviewer + rewriter,
			modelInvocationRows = writer + reviewer + rewriter,
			modelInvocationDistinctKeys = writer + reviewer + rewriter,
			artifactRows = if (reviewer == 0) 2 else 4,
			artifactDistinctKeys = if (reviewer == 0) 2 else 4,
			citationRows = citations,
			citationDistinctKeys = citations,
			interventionRows = interventions,
			interventionDistinctKeys = interventions,
			contentPackRows = packs,
			contentPackDistinctKeys = packs,
			exportEventRows = exports,
			exportEventDistinctKeys = exports,
		),
	)

	private fun hash(value: Char) = "sha256:${value.toString().repeat(64)}"
}
