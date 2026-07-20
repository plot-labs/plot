package com.plot.api.certification

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class CertificationArtifactContractTest {
	private val mapper = ObjectMapper()
	private val contract = CertificationArtifactContract(mapper)
	private val schema by lazy {
		mapper.readTree(
			Path.of("../..", "docs", "specs", "production-generation-certification-artifacts.schema.json").toFile(),
		)
	}
	private val examples by lazy {
		assertEquals(CertificationArtifactContract.SCHEMA_ID, schema["\$id"].stringValue())
		assertEquals(CertificationArtifactContract.SCHEMA_VERSION, schema["x-plot-schema-version"].stringValue())
		schema["examples"].asArray().values().toList()
	}

	@Test
	fun `scored basis point metrics stay aligned with the shared schema and runtime bounds`() {
		val basisPointKeys = setOf(
			"citationPrecisionBasisPoints", "citationRecallBasisPoints", "supportedClaimRecallBasisPoints",
			"unsupportedClaimRecallBasisPoints", "conflictRecallBasisPoints", "notRequiredFalsePositiveBasisPoints",
		)
		val schemaKeys = schema["\$defs"]["metrics"]["properties"].propertyNames().toSet()
		assertTrue(schemaKeys.containsAll(basisPointKeys))
		val campaign = contract.sealCampaign(campaignNode())
		val execution = contract.sealModelExecution(executionNode(campaign), campaign)
		val scored = evidenceNode(campaign, execution).deepCopy().asObject().also { node ->
			node.putObject("metrics").also { metrics -> basisPointKeys.forEach { metrics.put(it, 10_000) } }
		}
		assertEquals(basisPointKeys, contract.readEvidence(scored, campaign, execution).metrics.keys)
		val outOfRange = scored.deepCopy().asObject().also {
			it["metrics"].asObject().put("citationPrecisionBasisPoints", 10_001)
		}
		assertFailsWith<CertificationArtifactViolation> { contract.readEvidence(outOfRange, campaign, execution) }
	}

	@Test
	fun `shared schema examples seal into one campaign model execution and evidence lineage`() {
		val campaign = contract.sealCampaign(examples.single { it["artifactType"].stringValue() == "CAMPAIGN_MANIFEST" })
		val execution = contract.sealModelExecution(
			examples.single { it["artifactType"].stringValue() == "MODEL_EXECUTION_MANIFEST" },
			campaign,
		)
		val evidence = contract.readEvidence(
			examples.single { it["artifactType"].stringValue() == "EVIDENCE_ENVELOPE" },
			campaign,
			execution,
		)

		assertEquals("openai/gpt-5.4-nano", execution.artifact.requestedModel)
		assertEquals("openai/gpt-5.4-nano-2026-06-01", execution.artifact.servedModel)
		assertEquals(listOf("real-github-journey"), execution.artifact.scenarioIds)
		assertEquals(EvidenceOutcome.PASS, evidence.outcome)
		assertTrue(campaign.hash.startsWith("sha256:"))
		assertTrue(execution.hash.startsWith("sha256:"))
	}

	@Test
	fun `canonical sealing ignores key order and detects a post-seal mutation`() {
		val original = campaignNode()
		val reordered = mapper.readTree(mapper.writeValueAsString(original.properties().toList().reversed().associate { it.key to it.value }))
		val changed = original.deepCopy().asObject().also { it.set("reportId", mapper.readTree("\"report-bbbbbbbbbbbbbbbb\"")) }

		assertEquals(contract.sealCampaign(original).hash, contract.sealCampaign(reordered).hash)
		assertNotEquals(contract.sealCampaign(original).hash, contract.sealCampaign(changed).hash)
	}

	@Test
	fun `campaign requires a content-free source snapshot set hash and seals its changes`() {
		val original = campaignNode()
		val missing = original.deepCopy().asObject().also { it.remove("sourceSnapshotSetHash") }
		val changed = original.deepCopy().asObject().also {
			it.put("sourceSnapshotSetHash", "sha256:${"9".repeat(64)}")
		}

		assertFailsWith<CertificationArtifactViolation> { contract.sealCampaign(missing) }
		assertNotEquals(contract.sealCampaign(original).hash, contract.sealCampaign(changed).hash)
	}

	@Test
	fun `unknown private and raw provider fields are rejected before sealing`() {
		listOf("sourceBody", "privateUrl", "operatorNarrative", "rawRequestId").forEach { forbidden ->
			val unsafe = campaignNode().deepCopy().asObject().also { it.set(forbidden, mapper.readTree("\"secret\"")) }
			assertFailsWith<CertificationArtifactViolation> { contract.sealCampaign(unsafe) }
		}
	}

	@Test
	fun `wrong hash cross-campaign reference and duplicate identity fail bundle validation`() {
		val campaign = contract.sealCampaign(campaignNode())
		val execution = contract.sealModelExecution(executionNode(campaign), campaign)
		val evidence = contract.readEvidence(evidenceNode(campaign, execution), campaign, execution)
		val wrongHash = evidenceNode(campaign, execution).asObject().also {
			it.put("campaignManifestHash", "sha256:${"0".repeat(64)}")
		}
		assertFailsWith<CertificationArtifactViolation> { contract.readEvidence(wrongHash, campaign, execution) }

		val otherCampaign = contract.sealCampaign(campaignNode("campaign-bbbbbbbbbbbbbbbb", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
		assertFailsWith<CertificationArtifactViolation> { contract.readEvidence(evidenceNode(campaign, execution), otherCampaign, null) }
		assertFailsWith<CertificationArtifactViolation> {
			contract.validateBundle(campaign, listOf(execution), listOf(evidence, evidence))
		}
		assertFailsWith<CertificationArtifactViolation> {
			contract.validateBundle(campaign, listOf(execution), listOf(evidence.copy(campaignId = "campaign-bbbbbbbbbbbbbbbb")))
		}
		assertFailsWith<CertificationArtifactViolation> {
			contract.validateBundle(
				campaign,
				listOf(execution),
				listOf(evidence.copy(campaignManifestHash = "sha256:${"0".repeat(64)}")),
			)
		}
		assertFailsWith<CertificationArtifactViolation> {
			contract.validateBundle(
				campaign,
				listOf(execution),
				listOf(evidence.copy(modelExecutionId = "model-execution-bbbbbbbbbbbbbbbb")),
			)
		}
	}

	@Test
	fun `evidence attribution cannot drift from the sealed served model`() {
		val campaign = contract.sealCampaign(campaignNode())
		val execution = contract.sealModelExecution(executionNode(campaign), campaign)
		assertFailsWith<CertificationArtifactViolation> {
			contract.readEvidence(
				evidenceNode(campaign, execution, servedModel = "openai/gpt-5.4-nano-2026-06-02"),
				campaign,
				execution,
			)
		}
	}

	@Test
	fun `execution seals one route process namespace and a unique non-empty scenario set`() {
		val campaign = contract.sealCampaign(campaignNode())
		val invalid = executionNode(campaign).also {
			it.asObject().putArray("scenarioIds").add("real-github-journey").add("real-github-journey")
		}

		assertFailsWith<CertificationArtifactViolation> { contract.sealModelExecution(invalid, campaign) }
	}

	@Test
	fun `only inconclusive evidence can be replaced and a new revision cannot count old evidence`() {
		val campaign = contract.sealCampaign(campaignNode())
		val execution = contract.sealModelExecution(
			executionNode(campaign, listOf("real-github-journey", "unsupported-claim")),
			campaign,
		)
		val inconclusive = contract.readEvidence(
			evidenceNode(campaign, execution, outcome = "INCONCLUSIVE", artifactId = "artifact-cccccccccccccccc"),
			campaign,
			execution,
		)
		val replacement = contract.readEvidence(
			evidenceNode(
				campaign,
				execution,
				artifactId = "artifact-dddddddddddddddd",
				attemptId = "attempt-bbbbbbbbbbbbbbbb",
				ordinal = 1,
				lineageArtifactId = inconclusive.artifactId,
				lineageAttemptId = "attempt-aaaaaaaaaaaaaaaa",
			),
			campaign,
			execution,
			inconclusive,
		)
		assertEquals(inconclusive.artifactId, replacement.lineage?.priorArtifactId)
		assertEquals(inconclusive.ordinal, replacement.ordinal)

		assertFailsWith<CertificationArtifactViolation> {
			contract.readEvidence(
				evidenceNode(
					campaign,
					execution,
					artifactId = "artifact-eeeeeeeeeeeeeeee",
					attemptId = "attempt-cccccccccccccccc",
					scenarioId = "unsupported-claim",
					ordinal = 1,
					lineageArtifactId = inconclusive.artifactId,
					lineageAttemptId = inconclusive.attemptId,
				),
				campaign,
				execution,
				inconclusive,
			)
		}
		assertFailsWith<CertificationArtifactViolation> {
			contract.readEvidence(
				evidenceNode(
					campaign,
					execution,
					artifactId = "artifact-ffffffffffffffff",
					attemptId = "attempt-dddddddddddddddd",
					ordinal = 2,
					lineageArtifactId = inconclusive.artifactId,
					lineageAttemptId = inconclusive.attemptId,
				),
				campaign,
				execution,
				inconclusive,
			)
		}

		val hardFailure = inconclusive.copy(outcome = EvidenceOutcome.HARD_GATE_FAIL)
		assertFailsWith<CertificationArtifactViolation> {
			contract.readEvidence(
				evidenceNode(
					campaign,
					execution,
					artifactId = "artifact-1111111111111111",
					attemptId = "attempt-eeeeeeeeeeeeeeee",
					ordinal = 1,
					lineageArtifactId = hardFailure.artifactId,
					lineageAttemptId = hardFailure.attemptId,
				),
				campaign,
				execution,
				hardFailure,
			)
		}

		val nextCampaignNode = campaignNode("campaign-cccccccccccccccc", "cccccccccccccccccccccccccccccccccccccccc").asObject().also {
			it.putObject("revisionLineage").apply {
				put("relation", "SUPERSEDES_REVISION")
				put("priorCampaignId", campaign.artifact.campaignId)
				put("priorSourceRevision", campaign.artifact.sourceRevision)
				put("priorCampaignManifestHash", campaign.hash)
			}
		}
		val nextCampaign = contract.sealCampaign(nextCampaignNode, campaign)
		assertFailsWith<CertificationArtifactViolation> { contract.readEvidence(evidenceNode(campaign, execution), nextCampaign, null) }
	}

	private fun campaignNode(
		campaignId: String = "campaign-aaaaaaaaaaaaaaaa",
		sourceRevision: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	): JsonNode = mapper.valueToTree(mapOf(
		"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
		"artifactType" to "CAMPAIGN_MANIFEST",
		"artifactId" to "artifact-aaaaaaaaaaaaaaaa",
		"campaignId" to campaignId,
		"sealedAt" to "2026-07-16T00:00:00Z",
		"sourceRevision" to sourceRevision,
		"corpusHash" to "sha256:${"1".repeat(64)}",
		"profileHash" to "sha256:${"2".repeat(64)}",
		"sourceSnapshotSetHash" to "sha256:${"7".repeat(64)}",
		"environmentFingerprint" to "sha256:${"3".repeat(64)}",
		"reportId" to "report-aaaaaaaaaaaaaaaa",
		"approvedSourceAliases" to listOf("source-aaaaaaaaaaaaaaaa"),
	))

	private fun executionNode(
		campaign: SealedArtifact<CampaignManifest>,
		scenarioIds: List<String> = listOf("real-github-journey"),
	): JsonNode = mapper.valueToTree(mapOf(
		"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
		"artifactType" to "MODEL_EXECUTION_MANIFEST",
		"artifactId" to "artifact-bbbbbbbbbbbbbbbb",
		"campaignId" to campaign.artifact.campaignId,
		"campaignManifestHash" to campaign.hash,
		"modelExecutionId" to "model-execution-aaaaaaaaaaaaaaaa",
		"sealedAt" to "2026-07-16T00:01:00Z",
		"requestedModel" to "openai/gpt-5.4-nano",
		"servedModel" to "openai/gpt-5.4-nano-2026-06-01",
		"modelProfileHash" to "sha256:${"4".repeat(64)}",
		"pinnedUpstream" to "openai",
		"routePolicyHash" to "sha256:${"5".repeat(64)}",
		"processIdentity" to "process-aaaaaaaaaaaaaaaa",
		"sourceNamespace" to "namespace-aaaaaaaaaaaaaaaa",
		"idempotencyNamespace" to "namespace-bbbbbbbbbbbbbbbb",
		"scenarioIds" to scenarioIds,
	))

	private fun evidenceNode(
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>,
		outcome: String = "PASS",
		artifactId: String = "artifact-cccccccccccccccc",
		attemptId: String = "attempt-aaaaaaaaaaaaaaaa",
		scenarioId: String = "real-github-journey",
		servedModel: String = "openai/gpt-5.4-nano-2026-06-01",
		ordinal: Int = 1,
		lineageArtifactId: String? = null,
		lineageAttemptId: String? = null,
	): JsonNode {
		val values = linkedMapOf<String, Any>(
			"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
			"artifactType" to "EVIDENCE_ENVELOPE",
			"artifactId" to artifactId,
			"campaignId" to campaign.artifact.campaignId,
			"campaignManifestHash" to campaign.hash,
			"modelExecutionId" to execution.artifact.modelExecutionId,
			"modelExecutionManifestHash" to execution.hash,
			"recordedAt" to "2026-07-16T00:02:00Z",
			"evidenceType" to "MODEL_ATTEMPT",
			"subjectType" to "ATTEMPT",
			"attemptId" to attemptId,
			"scenarioId" to scenarioId,
			"ordinal" to ordinal,
			"outcome" to outcome,
			"metrics" to mapOf("latencyMs" to 1234, "promptTokens" to 10, "completionTokens" to 20),
			"codes" to emptyList<String>(),
			"attribution" to mapOf(
				"requestedModel" to "openai/gpt-5.4-nano",
				"servedModel" to servedModel,
				"observedUpstream" to "openai",
				"responseIdHash" to "sha256:${"6".repeat(64)}",
			),
		)
		if (lineageArtifactId != null && lineageAttemptId != null) {
			values["lineage"] = mapOf(
				"relation" to "REPLACES_INCONCLUSIVE",
				"priorArtifactId" to lineageArtifactId,
				"priorAttemptId" to lineageAttemptId,
			)
		}
		return mapper.valueToTree(values)
	}
}
