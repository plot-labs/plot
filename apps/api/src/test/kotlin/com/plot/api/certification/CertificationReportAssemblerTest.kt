package com.plot.api.certification

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

class CertificationReportAssemblerTest {
	private val mapper = jacksonObjectMapper()
	private val contract = CertificationArtifactContract(mapper)
	private val assembler = CertificationReportAssembler(mapper)

	@Test
	fun `assembler derives attempts scenarios and metrics only from immutable evidence`() {
		val fixture = fixture()
		val report = assemble(fixture)

		assertEquals(2, report.models.size)
		assertEquals(3, report.models.first().attempts.size)
		assertEquals(listOf("case-one"), report.models.first().attempts.first().scenarios.map { it.scenarioId })
		assertEquals(101, report.models.first().attempts.first().metrics.costUsdMicros)
		assertEquals(fixture.nano.artifact.modelExecutionId, report.selectedModelExecutionId)
		val restart = assembler.selectRestartCandidate(
			fixture.campaignNode, listOf(fixture.nanoNode, fixture.miniNode), fixture.evidence, fixture.reconciliations,
		)
		assertEquals(report.selectedModelExecutionId, restart.eligibleModelExecutionId)
		assertEquals(fixture.restart.modelExecutionId, restart.restartModelExecutionId)
	}

	@Test
	fun `missing duplicate mixed execution and browser attempt injection are rejected`() {
		val fixture = fixture()
		assertFailsWith<CertificationReportAssemblyException> {
			assemble(fixture, evidence = fixture.evidence.drop(1))
		}
		assertFailsWith<CertificationReportAssemblyException> {
			assemble(fixture, evidence = fixture.evidence + fixture.evidence.first())
		}
		val mixed = fixture.evidence.first().deepCopy().also { node ->
			node as tools.jackson.databind.node.ObjectNode
			node.put("modelExecutionId", fixture.mini.artifact.modelExecutionId)
			node.put("modelExecutionManifestHash", fixture.mini.hash)
		}
		assertFailsWith<RuntimeException> {
			assemble(fixture, evidence = listOf(mixed) + fixture.evidence.drop(1))
		}
		assertFailsWith<CertificationReportAssemblyException> {
			assemble(fixture, reconciliations = listOf(
				fixture.reconciliations.first().copy(attemptId = "attempt-ffffffffffffffff"),
			) + fixture.reconciliations.drop(1))
		}
		assertFailsWith<CertificationReportAssemblyException> {
			assemble(fixture, reconciliations = listOf(
				fixture.reconciliations.first().copy(replacesAttemptId = "attempt-ffffffffffffffff"),
			) + fixture.reconciliations.drop(1))
		}
	}

	@Test
	fun `unknown operator supplied metric cannot enter assembly`() {
		val fixture = fixture()
		val injected = fixture.evidence.first().deepCopy().also { node ->
			(node.get("metrics") as tools.jackson.databind.node.ObjectNode).put("operatorQualityScore", 10_000)
		}
		assertFailsWith<CertificationArtifactViolation> {
			assemble(fixture, evidence = listOf(injected) + fixture.evidence.drop(1))
		}
	}

	@Test
	fun `browser infrastructure replacement selects a fresh full model attempt in the same ordinal`() {
		val fixture = fixture()
		val replacementEvidence = evidenceNode(contract.sealCampaign(fixture.campaignNode), fixture.nano, 1, 7)
		val original = fixture.reconciliations.first()
		val replacement = original.copy(
			attemptId = attempt(7),
			replacesAttemptId = original.attemptId,
			replacementModelResultHash = hash('7'),
			outcome = EvidenceOutcome.PASS,
			codes = listOf(ReconciliationCode.RECONCILED),
		)
		val browser = listOf(
			original.copy(
				outcome = EvidenceOutcome.INCONCLUSIVE,
				codes = listOf(ReconciliationCode.BROWSER_INFRASTRUCTURE_INCONCLUSIVE),
				durableModelCallCount = 0,
				durableCitationCount = 0,
				durableInterventionCount = 0,
				durableExportEventCount = 0,
			),
			replacement,
		) + fixture.reconciliations.drop(1)

		val report = assemble(fixture, evidence = fixture.evidence + listOf(replacementEvidence), reconciliations = browser)

		assertEquals(fixture.nano.artifact.modelExecutionId, report.selectedModelExecutionId)
		assertEquals(4, report.models.first().attempts.size)
	}

	private fun assemble(
		fixture: Fixture,
		evidence: List<JsonNode> = fixture.evidence,
		reconciliations: List<CertificationReconciliationResult> = fixture.reconciliations,
	) = assembler.assemble(
		ReportPhase.FINAL,
		fixture.campaignNode,
		listOf(fixture.nanoNode, fixture.miniNode),
		evidence,
		reconciliations,
		fixture.deterministic,
		fixture.restart,
		fixture.cleanup,
		fixture.operator,
	)

	private fun fixture(): Fixture {
		val campaignNode = mapper.valueToTree<JsonNode>(mapOf(
			"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
			"artifactType" to "CAMPAIGN_MANIFEST",
			"artifactId" to "artifact-${"1".repeat(16)}",
			"campaignId" to CAMPAIGN,
			"sealedAt" to "2026-07-16T00:00:00Z",
			"sourceRevision" to REVISION,
			"corpusHash" to hash('1'),
			"profileHash" to hash('2'),
			"sourceSnapshotSetHash" to hash('3'),
			"environmentFingerprint" to hash('4'),
			"reportId" to "report-${"1".repeat(16)}",
			"approvedSourceAliases" to listOf("source-${"1".repeat(16)}"),
		))
		val campaign = contract.sealCampaign(campaignNode)
		val nanoNode = executionNode(campaign, NANO, "openai/gpt-5.4-nano", '5')
		val miniNode = executionNode(campaign, MINI, "openai/gpt-4o-mini-2024-07-18", '6')
		val nano = contract.sealModelExecution(nanoNode, campaign)
		val mini = contract.sealModelExecution(miniNode, campaign)
		val evidence = listOf(nano, mini).flatMapIndexed { modelIndex, execution ->
			(1..3).map { ordinal -> evidenceNode(campaign, execution, ordinal, modelIndex * 3 + ordinal) }
		}
		val reconciliations = listOf(nano, mini).flatMapIndexed { modelIndex, execution ->
			(1..3).map { ordinal ->
				CertificationReconciliationResult(
					campaignId = CAMPAIGN,
					campaignManifestHash = campaign.hash,
					modelExecutionId = execution.artifact.modelExecutionId,
					modelExecutionManifestHash = execution.hash,
					sourceSnapshotSetHash = campaign.artifact.sourceSnapshotSetHash,
					attemptId = attempt(modelIndex * 3 + ordinal),
					scenarioId = "real-github-journey",
					ordinal = ordinal,
					replacesAttemptId = null,
					outcome = EvidenceOutcome.PASS,
					codes = listOf(ReconciliationCode.RECONCILED),
					durableModelCallCount = 2,
					durableCitationCount = 2,
					durableInterventionCount = 1,
					durableExportEventCount = 2,
				)
			}
		}
		val operator = CertificationOperatorDecision(
			"operator-${"1".repeat(16)}", "2026-07-16T00:02:00Z", OperatorDecision.GO,
			CertificationOperatorRubric(5, 5, 5, HedgingRating.APPROPRIATE),
		)
		val cleanup = CertificationCleanupGate().evaluate(CertificationCleanupObservation(
			CAMPAIGN, campaign.hash, REVISION, "2026-07-16T00:01:00Z", operator.operatorAlias,
			"2026-07-16T00:00:30Z", 0, true, true, true, true, true, DatabaseDisposition.DESTROYED,
		), Instant.parse("2026-07-16T00:01:00Z"))
		return Fixture(
			campaignNode, nanoNode, miniNode, nano, mini, evidence, reconciliations,
			CertificationDeterministicResult(
				sourceRevision = REVISION, campaignId = CAMPAIGN, campaignManifestHash = campaign.hash,
				corpusHash = hash('1'), profileHash = hash('2'), outcome = EvidenceOutcome.PASS,
			),
			CertificationProcessRestartResult(
				CAMPAIGN, campaign.hash, NANO, nano.hash, "attempt-${"9".repeat(16)}", EvidenceOutcome.PASS,
				ProcessRestartCode.PROCESS_RESTART_RECONCILED, RestartCheckpointArtifact.WRITER_OUTPUT,
				hash('3'), 2, 2, 1, 1, 0,
			),
			cleanup, operator,
		)
	}

	private fun executionNode(
		campaign: SealedArtifact<CampaignManifest>, executionId: String, requestedModel: String, value: Char,
	) = mapper.valueToTree<JsonNode>(mapOf(
		"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
		"artifactType" to "MODEL_EXECUTION_MANIFEST",
		"artifactId" to "artifact-${value.toString().repeat(16)}",
		"campaignId" to CAMPAIGN,
		"campaignManifestHash" to campaign.hash,
		"modelExecutionId" to executionId,
		"sealedAt" to "2026-07-16T00:00:00Z",
		"requestedModel" to requestedModel,
		"servedModel" to requestedModel,
		"modelProfileHash" to hash(value),
		"pinnedUpstream" to "openai",
		"routePolicyHash" to hash(value),
		"processIdentity" to "process-${value.toString().repeat(16)}",
		"sourceNamespace" to "namespace-${value.toString().repeat(16)}",
		"idempotencyNamespace" to "namespace-${value.toString().repeat(16)}",
		"scenarioIds" to listOf("case-one", "real-github-journey", "process-restart"),
	))

	private fun evidenceNode(
		campaign: SealedArtifact<CampaignManifest>, execution: SealedArtifact<ModelExecutionManifest>, ordinal: Int, id: Int,
	) = mapper.valueToTree<JsonNode>(mapOf(
		"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
		"artifactType" to "EVIDENCE_ENVELOPE",
		"artifactId" to "artifact-${(if (id in 1..6) "234789"[id - 1] else 'a').toString().repeat(16)}",
		"campaignId" to CAMPAIGN,
		"campaignManifestHash" to campaign.hash,
		"modelExecutionId" to execution.artifact.modelExecutionId,
		"modelExecutionManifestHash" to execution.hash,
		"recordedAt" to "2026-07-16T00:00:00Z",
		"evidenceType" to "MODEL_ATTEMPT",
		"subjectType" to "ATTEMPT",
		"attemptId" to attempt(id),
		"scenarioId" to "case-one",
		"ordinal" to ordinal,
		"outcome" to "PASS",
		"metrics" to metrics(id),
		"codes" to emptyList<String>(),
	))

	private fun metrics(id: Int) = mapOf(
		"coldStart" to (id % 3 == 1), "promptTokens" to 10, "completionTokens" to 5,
		"reasoningTokens" to 1, "cachedTokens" to 0, "costUsdMicros" to 100 + id,
		"latencyMs" to 200, "rewriteCount" to 0, "modelCallCount" to 2,
		"citationPrecisionBasisPoints" to 10_000, "citationRecallBasisPoints" to 10_000,
		"supportedClaimRecallBasisPoints" to 10_000, "unsupportedClaimRecallBasisPoints" to 10_000,
		"conflictRecallBasisPoints" to 10_000, "notRequiredFalsePositiveBasisPoints" to 0,
	)

	private fun attempt(id: Int) = "attempt-${id.toString().repeat(16)}"
	private fun hash(value: Char) = "sha256:${value.toString().repeat(64)}"

	private data class Fixture(
		val campaignNode: JsonNode,
		val nanoNode: JsonNode,
		val miniNode: JsonNode,
		val nano: SealedArtifact<ModelExecutionManifest>,
		val mini: SealedArtifact<ModelExecutionManifest>,
		val evidence: List<JsonNode>,
		val reconciliations: List<CertificationReconciliationResult>,
		val deterministic: CertificationDeterministicResult,
		val restart: CertificationProcessRestartResult,
		val cleanup: CertificationCleanupResult,
		val operator: CertificationOperatorDecision,
	)

	companion object {
		private const val CAMPAIGN = "campaign-aaaaaaaaaaaaaaaa"
		private const val NANO = "model-execution-aaaaaaaaaaaaaaaa"
		private const val MINI = "model-execution-bbbbbbbbbbbbbbbb"
		private const val REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	}
}
