package com.plot.api.artifact.workflow

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.SourceProvider
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
	properties = [
		"plot.dev-bootstrap.enabled=true",
		"plot.ai.enabled=false",
		"plot.ai.worker-enabled=true",
		"plot.ai.worker-poll-delay=50ms",
	],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArtifactWorkflowContinuousClaimantIntegrationTest {
	@Autowired private lateinit var persistence: ArtifactWorkflowAdmissionPersistence
	@Autowired private lateinit var workflow: ArtifactWorkflowService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	@Test
	fun queuedRunCreatedAfterStartupIsClaimedWithoutAnExplicitDispatch() {
		val runId = reserve()

		assertTrue(awaitTerminal(runId), "scheduled claimant did not process the queued run")
		assertEquals(
			"FAILED:MODEL_NOT_CONFIGURED",
			jdbcTemplate.queryForObject(
				"select status || ':' || error_code from generation_runs where id = ?",
				String::class.java,
				runId,
			),
		)
	}

	private fun awaitTerminal(runId: UUID): Boolean {
		val deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos()
		while (System.nanoTime() < deadline) {
			val status = jdbcTemplate.queryForObject(
				"select status from generation_runs where id = ?",
				String::class.java,
				runId,
			)
			if (status == "FAILED") return true
			Thread.sleep(25)
		}
		return false
	}

	private fun reserve(): UUID {
		val key = UUID.randomUUID().toString()
		val runId = UUID.randomUUID()
		val blockId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into writing_blocks (id, workspace_id, source_origin, source_kind, title, body, url,
			 content_hash, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'github', 'pull_request', ?, 'evidence', ?, ?, now(), 'ACTIVE', ?, now(), now())
			""".trimIndent(),
			blockId,
			devContext.devWorkspaceId,
			"PR $key",
			"https://github.test/acme/repo/pull/$key",
			"block-$key",
			devContext.devUserId,
		)
		val state = workflow.start(
			runId,
			listOf(
				EvidenceSnapshot(
					id = UUID.randomUUID(),
					artifactWorkflowRunId = runId,
					writingBlockId = blockId,
					orderIndex = 0,
					sourceProvider = SourceProvider.GITHUB,
					sourceKind = "pull_request",
					sourceLabel = "PR $key",
					snapshotTitle = "PR $key",
					snapshotBody = "Shipped evidence",
					snapshotExcerpt = "Shipped evidence",
					originalUrl = "https://github.test/acme/repo/pull/$key",
					sourceCreatedAt = null,
					sourceUpdatedAt = null,
					contentHash = "hash-$key",
					capturedAt = Instant.now(),
				),
			),
			null,
		)
		persistence.createRun(
			ArtifactWorkflowRunReservation(
				workspaceId = devContext.devWorkspaceId,
				createdByUserId = devContext.devUserId,
				sourceScopeId = null,
				idempotencyKey = "continuous-$key",
				requestFingerprint = "fingerprint-$key",
				state = state,
				provider = "OPENAI",
				modelName = "disabled",
				budgetJson = """{"maxModelCalls":12,"maxTotalTokens":80000,"maxRunDurationMillis":300000}""",
			),
		)
		return runId
	}
}
