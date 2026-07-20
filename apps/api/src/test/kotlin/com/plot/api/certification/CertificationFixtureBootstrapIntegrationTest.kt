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
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.WriterOutput
import com.plot.api.generation.model.WriterSentence
import com.plot.api.writingblock.WritingBlockImportService
import com.plot.api.writingblock.WritingBlockRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.annotation.DirtiesContext
import tools.jackson.databind.ObjectMapper

@SpringBootTest(classes = [ApiApplication::class])
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=false", "server.address=127.0.0.1"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CertificationFixtureBootstrapIntegrationTest {
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var writingBlockImportService: WritingBlockImportService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var repository: WritingBlockRepository
	@Autowired private lateinit var snapshotService: EvidenceSnapshotService
	@Autowired private lateinit var workflow: GenerationWorkflowService
	@Autowired private lateinit var persistence: GenerationPersistence
	@Autowired private lateinit var mapper: ObjectMapper
	@Autowired private lateinit var applicationContext: ApplicationContext

	@Test
	fun `activation is fail closed and ordinary Spring context has no certification beans`() {
		val guard = CertificationActivationGuard()
		val id = "campaign-aaaaaaaaaaaaaaaa"
		val fingerprint = certificationDatabaseFingerprint("plot_cert_aaaaaaaa", "one-time-token")
		val baseline = CertificationDatabaseBaseline("plot_cert_aaaaaaaa", "127.0.0.1", fingerprint, 0)
		val valid = properties(id, fingerprint)

		assertEquals(id, guard.authorize(valid, baseline).certificationId)
		listOf(
			valid.copy(enabled = false),
			valid.copy(activeProfiles = emptySet()),
			valid.copy(serverAddress = "0.0.0.0"),
			valid.copy(managementServerAddress = "10.0.0.1"),
			valid.copy(externalHost = "public.example"),
			valid.copy(forwardedHost = "public.example"),
			valid.copy(forwardedHost = "localhost, public.example"),
			valid.copy(devBootstrapEnabled = true),
			valid.copy(certificationId = null),
			valid.copy(expectedDatabaseFingerprint = sha256("other")),
		).forEach { rejected ->
			assertFailsWith<CertificationActivationException> { guard.authorize(rejected, baseline) }
		}
		assertFailsWith<CertificationActivationException> {
			guard.authorize(valid, baseline.copy(applicationRowCount = 1))
		}
		assertFailsWith<CertificationActivationException> {
			guard.authorize(valid, baseline.copy(databaseName = "plot"))
		}
		assertTrue(applicationContext.beanDefinitionNames.none { it.contains("certification", ignoreCase = true) })
		assertFalse(applicationContext.containsBeanDefinition("certificationFixtureBootstrap"))
	}

	@Test
	fun `fixture bootstrap is idempotent seals snapshots and observer fires after durable commit`() {
		val authorization = authorization()
		val bootstrap = CertificationFixtureBootstrap(
			authorization, jdbcTemplate, writingBlockImportService, devContext, mapper,
		)
		val executionA = "model-execution-aaaaaaaaaaaaaaaa"
		val first = bootstrap.bootstrap(executionA)
		val repeated = bootstrap.bootstrap(executionA)
		val executionB = bootstrap.bootstrap("model-execution-bbbbbbbbbbbbbbbb")

		assertEquals(first, repeated)
		assertEquals(3, first.sources.size)
		assertTrue(first.sources.map { it.writingBlockId }.toSet().intersect(executionB.sources.map { it.writingBlockId }.toSet()).isEmpty())
		assertNotEquals(first.sourceSnapshotSetHash, executionB.sourceSnapshotSetHash)
		bootstrap.verifySnapshotSet(first)

		val runId = UUID.randomUUID()
		val evidence = first.sources.sortedBy { it.alias }.mapIndexed { index, source ->
			snapshotService.snapshot(runId, index, requireNotNull(repository.findByWorkspaceIdAndId(devContext.devWorkspaceId, source.writingBlockId)))
		}
		val idempotencyKey = "${first.idempotencyNamespace}:attempt-bbbbbbbbbbbbbbbb"
		persistence.createRun(GenerationRunReservation(
			devContext.devWorkspaceId,
			devContext.devUserId,
			null,
			idempotencyKey,
			"fingerprint-${first.modelExecutionId}-observer",
			workflow.start(runId, evidence, null),
			"OPENROUTER",
			"openai/gpt-5.4-nano",
			"{\"maxModelCalls\":12}",
		))
		var observedAfterCommit = false
		val observer = com.plot.api.generation.GenerationCheckpointObserver { checkpoint ->
			assertEquals("WRITER_OUTPUT", checkpoint.artifactType)
			assertEquals("SUCCEEDED", jdbcTemplate.queryForObject(
				"select status from model_invocations where id = ?", String::class.java, checkpoint.invocationId,
			))
			assertEquals("REVIEWING", jdbcTemplate.queryForObject(
				"select status from generation_runs where id = ? and claimed_by is null", String::class.java, runId,
			))
			assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from generation_artifacts where generation_run_id = ? and artifact_type = 'WRITER_OUTPUT'",
				Int::class.java, runId,
			))
			observedAfterCommit = true
		}
		val worker = GenerationRunWorker(
			persistence, workflow, WriterOnlyGateway(), checkpointObserver = observer, workerId = "certification-observer",
		)

		assertTrue(worker.processOne())
		assertTrue(observedAfterCommit)
		assertEquals(GenerationRunStatus.REVIEWING, persistence.loadState(devContext.devWorkspaceId, runId).status)

		jdbcTemplate.update(
			"update writing_blocks set content_hash = ? where id = ?",
			"drifted", first.sources.first().writingBlockId,
		)
		assertFailsWith<CertificationSnapshotDriftException> { bootstrap.verifySnapshotSet(first) }
	}

	private fun authorization(): AuthorizedCertification {
		val id = "campaign-aaaaaaaaaaaaaaaa"
		val fingerprint = certificationDatabaseFingerprint("plot_cert_aaaaaaaa", "one-time-token")
		return CertificationActivationGuard().authorize(
			properties(id, fingerprint),
			CertificationDatabaseBaseline("plot_cert_aaaaaaaa", "127.0.0.1", fingerprint, 0),
		)
	}

	private fun properties(id: String, fingerprint: String) = CertificationProperties(
		enabled = true,
		activeProfiles = setOf("generation-certification"),
		serverAddress = "127.0.0.1",
		managementServerAddress = "::1",
		devBootstrapEnabled = false,
		certificationId = id,
		expectedDatabaseFingerprint = fingerprint,
	)
}

private class WriterOnlyGateway : GenerationModelGateway {
	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> = result(
		WriterOutput(listOf(WriterSentence("The synthetic release is ready."))),
	)

	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> = error("not expected")
	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> = error("not expected")

	private fun <T : Any> result(value: T) = ModelCallResult(
		value,
		ModelCallMetadata("private-response-id", "served-model", "stop", 1, 1, 2, Duration.ofMillis(1), emptyMap()),
	)
}
