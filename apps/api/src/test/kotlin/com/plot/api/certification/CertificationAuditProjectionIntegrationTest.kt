package com.plot.api.certification

import com.plot.api.ApiApplication
import com.plot.api.TestcontainersConfiguration
import com.plot.api.ai.provider.GenerationModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelCallResult
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.dev.DevContext
import com.plot.api.generation.EvidenceSnapshotService
import com.plot.api.generation.GenerationPersistence
import com.plot.api.generation.GenerationRunReservation
import com.plot.api.generation.GenerationRunStatus
import com.plot.api.generation.GenerationRunWorker
import com.plot.api.generation.GenerationWorkflowService
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.SentenceReview
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.WriterOutput
import com.plot.api.generation.model.WriterSentence
import com.plot.api.writingblock.WritingBlockImportService
import com.plot.api.writingblock.WritingBlockRepository
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper

@SpringBootTest(classes = [ApiApplication::class])
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=false", "server.address=127.0.0.1"])
class CertificationAuditProjectionIntegrationTest {
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var writingBlockImportService: WritingBlockImportService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var repository: WritingBlockRepository
	@Autowired private lateinit var snapshotService: EvidenceSnapshotService
	@Autowired private lateinit var workflow: GenerationWorkflowService
	@Autowired private lateinit var persistence: GenerationPersistence
	@Autowired private lateinit var mapper: ObjectMapper
	@TempDir lateinit var tempDir: Path

	@Test
	fun `audit joins durable run evidence and excludes bodies urls and raw identifiers`() {
		val authorization = authorization()
		val fixtures = CertificationFixtureBootstrap(
			authorization, jdbcTemplate, writingBlockImportService, devContext, mapper,
		).bootstrap("model-execution-aaaaaaaaaaaaaaaa")
		val runId = UUID.randomUUID()
		val evidence = fixtures.sources.sortedBy { it.alias }.mapIndexed { index, source ->
			snapshotService.snapshot(runId, index, requireNotNull(repository.findByWorkspaceIdAndId(devContext.devWorkspaceId, source.writingBlockId)))
		}
		val attemptId = "attempt-aaaaaaaaaaaaaaaa"
		val idempotencyKey = "${fixtures.idempotencyNamespace}:$attemptId"
		persistence.createRun(GenerationRunReservation(
			devContext.devWorkspaceId, devContext.devUserId, null, idempotencyKey, "audit-fingerprint",
			workflow.start(runId, evidence, null), "OPENROUTER", "openai/gpt-5.4-nano", "{\"maxModelCalls\":12}",
		))

		val gate = CertificationCheckpointGate(
			authorization, "WRITER_OUTPUT", CertificationCheckpointGateMode.FAIL, Duration.ofSeconds(1),
		)
		assertFailsWith<CertificationCheckpointReachedException> {
			GenerationRunWorker(
				persistence, workflow, AuditGateway(evidence.first().id), checkpointObserver = gate, workerId = "audit-writer",
			).processOne()
		}
		assertTrue(gate.awaitReached(Duration.ofMillis(50)))
		assertEquals(GenerationRunStatus.REVIEWING, persistence.loadState(devContext.devWorkspaceId, runId).status)
		GenerationRunWorker(
			persistence, workflow, AuditGateway(evidence.first().id), workerId = "audit-reviewer",
		).processOne()
		assertEquals(GenerationRunStatus.READY, persistence.loadState(devContext.devWorkspaceId, runId).status)

		val variantId = requireNotNull(jdbcTemplate.queryForObject(
			"select id from content_variants where generation_run_id = ?", UUID::class.java, runId,
		))
		insertExport(runId, variantId, "REJECTED", 1, false, null, Instant.parse("2026-07-16T00:00:00Z"))
		insertExport(runId, variantId, "SUCCEEDED", 1, true, sha256("export"), Instant.parse("2026-07-16T00:00:01Z"))
		val identity = CertificationAttemptIdentity(
			authorization.certificationId,
			"sha256:${"a".repeat(64)}",
			fixtures.modelExecutionId,
			"sha256:${"b".repeat(64)}",
			attemptId,
			"real-github-journey",
			1,
			devContext.devWorkspaceId,
			runId,
			idempotencyKey,
			fixtures.sourceSnapshotSetHash,
		)
		val projection = CertificationAuditProjection(authorization, jdbcTemplate)
		val audit = projection.project(identity)
		val execution = ModelExecutionManifest(
			"artifact-aaaaaaaaaaaaaaaa", authorization.certificationId, identity.campaignManifestHash,
			fixtures.modelExecutionId, "2026-07-16T00:00:00Z", "openai/gpt-5.4-nano",
			"openai/gpt-5.4-nano-2026-06-01", sha256("profile"), "openai", sha256("route"),
			"process-aaaaaaaaaaaaaaaa", "namespace-aaaaaaaaaaaaaaaa", fixtures.idempotencyNamespace,
			listOf("real-github-journey"),
		)
		val attributed = CertificationInvocationAttributor(jdbcTemplate, mapper) { responseId ->
			OpenRouterGenerationAttribution(
				servedModel = execution.servedModel,
				providerSlug = execution.pinnedUpstream,
				nativeTokens = NativeTokenUsage(1, 1, 0, 0),
				costUsdMicros = 2,
				latencyMs = 1,
				generationTimeMs = 1,
				finishReason = "stop",
				byok = false,
				responseIdHash = responseHash(responseId),
				upstreamIdHash = sha256("upstream"),
			)
		}.attribute(identity, execution)

		assertEquals("READY", audit.runStatus)
		assertEquals(listOf("WRITER", "REVIEWER"), audit.invocations.map { it.role })
		assertEquals(1, audit.citationCount)
		assertEquals(1, audit.sentenceVerdictCounts["SUPPORTED"])
		assertEquals(listOf("REJECTED", "SUCCEEDED"), audit.exports.map { it.status })
		assertEquals(listOf(false, true), audit.exports.map { it.warningAcknowledged })
		assertEquals(listOf(EvidenceOutcome.PASS, EvidenceOutcome.PASS), attributed.map { it.outcome })
		val json = mapper.writeValueAsString(audit.copy(invocationAttributions = attributed))
		assertFalse(json.contains(runId.toString()))
		assertFalse(json.contains("private-response-id"))
		assertFalse(json.contains("example.invalid"))
		assertFalse(json.contains("synthetic", ignoreCase = true))

		val writer = CertificationAuditEnvelopeWriter(tempDir, mapper)
		val path = writer.write(audit)
		assertTrue(path.toFile().isFile)
		assertFailsWith<CertificationAuditWriteException> { writer.write(audit) }

		insertExport(runId, variantId, "SUCCEEDED", 1, true, sha256("duplicate"), Instant.parse("2026-07-16T00:00:02Z"))
		assertFailsWith<CertificationAuditReconciliationException> { projection.project(identity) }
	}

	private fun insertExport(
		runId: UUID,
		variantId: UUID,
		status: String,
		unresolvedCount: Int,
		warningAcknowledged: Boolean,
		outputHash: String?,
		createdAt: Instant,
	) {
		jdbcTemplate.update(
			"""
			insert into generation_export_events (id, workspace_id, generation_run_id, content_variant_id, format,
			 disposition, status, unresolved_count, warning_acknowledged, output_content_hash, failure_code, created_by_user_id, created_at)
			values (?, ?, ?, ?, 'MARKDOWN', 'DOWNLOAD', ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			UUID.randomUUID(), devContext.devWorkspaceId, runId, variantId, status, unresolvedCount,
			warningAcknowledged, outputHash, if (status == "REJECTED") "EXPORT_CONFIRMATION_REQUIRED" else null,
			devContext.devUserId, Timestamp.from(createdAt),
		)
	}

	private fun authorization(): AuthorizedCertification {
		val id = "campaign-aaaaaaaaaaaaaaaa"
		val fingerprint = certificationDatabaseFingerprint("plot_cert_aaaaaaaa", "one-time-token")
		return CertificationActivationGuard().authorize(
			CertificationProperties(
				true, setOf("generation-certification"), "127.0.0.1", "::1",
				devBootstrapEnabled = false, certificationId = id, expectedDatabaseFingerprint = fingerprint,
			),
			CertificationDatabaseBaseline("plot_cert_aaaaaaaa", "127.0.0.1", fingerprint, 0),
		)
	}

	private fun responseHash(value: String): String = "sha256:" + HexFormat.of().formatHex(
		MessageDigest.getInstance("SHA-256").digest("openrouter-response:$value".toByteArray()),
	)
}

private class AuditGateway(private val evidenceId: UUID) : GenerationModelGateway {
	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> = result(
		WriterOutput(listOf(WriterSentence("The controlled release evidence is supported."))),
	)

	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> = result(
		ReviewerOutput(listOf(SentenceReview(request.sentences.single().id, ReviewVerdict.SUPPORTED, listOf(evidenceId)))),
	)

	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> = error("not expected")

	private fun <T : Any> result(value: T) = ModelCallResult(
		value,
		ModelCallMetadata(
			"private-response-id", "openai/gpt-5.4-nano-2026-06-01", "stop", 1, 1, 2, Duration.ofMillis(1),
			mapOf(
				"gateway" to "openrouter",
				"requestedModel" to "openai/gpt-5.4-nano",
				"servedModel" to "openai/gpt-5.4-nano-2026-06-01",
				"responseId" to "private-response-id",
				"finishReason" to "stop",
			),
		),
	)
}
