package com.plot.api.certification

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class LiveModelResultWriterTest {
	@TempDir
	lateinit var tempDir: Path

	private val mapper = ObjectMapper()
	private val contract = CertificationArtifactContract(mapper)

	@Test
	fun `validated evidence is written once beneath the sealed campaign`() {
		val campaign = contract.sealCampaign(campaignNode())
		val execution = contract.sealModelExecution(executionNode(campaign), campaign)
		val node = evidenceNode(campaign, execution)
		val writer = LiveModelResultWriter(tempDir, mapper)

		val path = writer.write(node, campaign, execution)

		assertEquals(
			tempDir.resolve(campaign.artifact.campaignId).resolve("evidence").resolve("artifact-cccccccccccccccc.json"),
			path,
		)
		assertEquals(node, mapper.readTree(path.toFile()))
		assertFailsWith<ImmutableEvidenceWriteException> { writer.write(node, campaign, execution) }
	}

	@Test
	fun `unsafe fields and content are rejected before a file is created`() {
		val campaign = contract.sealCampaign(campaignNode())
		val execution = contract.sealModelExecution(executionNode(campaign), campaign)
		val unsafe = evidenceNode(campaign, execution).deepCopy().asObject().also {
			it.put("sourceBody", "https://example.invalid/private")
		}
		val writer = LiveModelResultWriter(tempDir, mapper)

		assertFailsWith<MachineResultValidationException> { writer.write(unsafe, campaign, execution) }
		assertFalse(tempDir.resolve(campaign.artifact.campaignId).resolve("evidence").exists())
	}

	private fun campaignNode(): JsonNode = mapper.valueToTree(mapOf(
		"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
		"artifactType" to "CAMPAIGN_MANIFEST",
		"artifactId" to "artifact-aaaaaaaaaaaaaaaa",
		"campaignId" to "campaign-aaaaaaaaaaaaaaaa",
		"sealedAt" to "2026-07-16T00:00:00Z",
		"sourceRevision" to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		"corpusHash" to "sha256:${"1".repeat(64)}",
		"profileHash" to "sha256:${"2".repeat(64)}",
		"sourceSnapshotSetHash" to "sha256:${"7".repeat(64)}",
		"environmentFingerprint" to "sha256:${"3".repeat(64)}",
		"reportId" to "report-aaaaaaaaaaaaaaaa",
		"approvedSourceAliases" to listOf("source-aaaaaaaaaaaaaaaa"),
	))

	private fun executionNode(campaign: SealedArtifact<CampaignManifest>): JsonNode = mapper.valueToTree(mapOf(
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
		"scenarioIds" to listOf("safe-scenario"),
	))

	private fun evidenceNode(
		campaign: SealedArtifact<CampaignManifest>,
		execution: SealedArtifact<ModelExecutionManifest>,
	): JsonNode = mapper.valueToTree(mapOf(
		"schemaVersion" to CertificationArtifactContract.SCHEMA_VERSION,
		"artifactType" to "EVIDENCE_ENVELOPE",
		"artifactId" to "artifact-cccccccccccccccc",
		"campaignId" to campaign.artifact.campaignId,
		"campaignManifestHash" to campaign.hash,
		"modelExecutionId" to execution.artifact.modelExecutionId,
		"modelExecutionManifestHash" to execution.hash,
		"recordedAt" to "2026-07-16T00:02:00Z",
		"evidenceType" to "MODEL_ATTEMPT",
		"subjectType" to "ATTEMPT",
		"attemptId" to "attempt-aaaaaaaaaaaaaaaa",
		"scenarioId" to "safe-scenario",
		"ordinal" to 1,
		"outcome" to "PASS",
		"metrics" to mapOf("latencyMs" to 1, "modelCallCount" to 2, "rewriteCount" to 0),
		"codes" to emptyList<String>(),
		"attribution" to mapOf(
			"requestedModel" to execution.artifact.requestedModel,
			"servedModel" to execution.artifact.servedModel,
			"observedUpstream" to execution.artifact.pinnedUpstream,
			"responseIdHash" to "sha256:${"6".repeat(64)}",
		),
	))
}
