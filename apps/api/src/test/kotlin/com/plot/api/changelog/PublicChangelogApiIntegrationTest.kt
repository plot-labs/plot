package com.plot.api.changelog

import com.plot.api.TestcontainersConfiguration
import com.plot.api.ai.provider.ArtifactWorkflowModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelCallResult
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.artifact.workflow.ArtifactWorkflowAdmissionPersistence
import com.plot.api.artifact.workflow.ArtifactWorkflowExecutionPersistence
import com.plot.api.artifact.workflow.ArtifactWorkflowQueryPersistence
import com.plot.api.artifact.workflow.ArtifactWorkflowRunReservation
import com.plot.api.artifact.workflow.ArtifactWorkflowRunWorker
import com.plot.api.artifact.workflow.ArtifactWorkflowService
import com.plot.api.artifact.workflow.model.EvidenceSnapshot
import com.plot.api.artifact.workflow.model.ReviewVerdict
import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.SentenceReview
import com.plot.api.artifact.workflow.model.SourceProvider
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import com.plot.api.artifact.workflow.model.WriterOutput
import com.plot.api.artifact.workflow.model.WriterSentence
import com.plot.api.dev.DevContext
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicChangelogApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var admissionPersistence: ArtifactWorkflowAdmissionPersistence
	@Autowired private lateinit var executionPersistence: ArtifactWorkflowExecutionPersistence
	@Autowired private lateinit var queryPersistence: ArtifactWorkflowQueryPersistence
	@Autowired private lateinit var workflow: ArtifactWorkflowService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	@org.junit.jupiter.api.BeforeEach
	fun cleanPublishedEntries() {
		jdbcTemplate.update("delete from published_changelog_entries where workspace_id = ?", devContext.devWorkspaceId)
	}

	@Test
	fun `public changelog exposes only published entries and unknown slug returns not found`() {
		val fixture = readyPack()
		mockMvc.get("/api/public/changelog/dev-workspace").andExpect {
			status { isOk() }
			jsonPath("$.workspaceSlug") { value("dev-workspace") }
			jsonPath("$.entries.length()") { value(0) }
		}
		mockMvc.get("/api/public/changelog/unknown-workspace").andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}

		val publish = mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
		}.andExpect { status { isOk() } }.andReturn().response.contentAsString
		val entrySlug = Regex(""""entrySlug"\s*:\s*"([^"]+)"""").find(publish)?.groupValues?.get(1)
			?: error("Missing entry slug")

		mockMvc.get("/api/public/changelog/dev-workspace").andExpect {
			status { isOk() }
			jsonPath("$.entries.length()") { value(1) }
			jsonPath("$.entries[0].entrySlug") { value(entrySlug) }
			jsonPath("$.entries[0].title") { exists() }
			jsonPath("$.entries[0].bodyMarkdown") { doesNotExist() }
		}
		mockMvc.get("/api/public/changelog/dev-workspace/$entrySlug").andExpect {
			status { isOk() }
			jsonPath("$.entrySlug") { value(entrySlug) }
			jsonPath("$.bodyMarkdown") { value("Supported sentence.\n\nStable sentence.\n") }
			jsonPath("$.workspaceSlug") { value("dev-workspace") }
			jsonPath("$.workspaceName") { exists() }
		}
		mockMvc.get("/api/public/changelog/dev-workspace/missing-entry").andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from published_changelog_entries where workspace_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
		))
	}

	private fun readyPack(): PublicFixture {
		val runId = UUID.randomUUID()
		val blockId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into writing_blocks (id, workspace_id, source_origin, source_kind, title, body, url,
			 content_hash, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'github', 'pull_request', 'PR', 'evidence', ?,
			 'block-hash', now(), 'ACTIVE', ?, now(), now())
			""".trimIndent(), blockId, devContext.devWorkspaceId, "https://github.test/acme/repo/pull/1", devContext.devUserId,
		)
		val evidence = EvidenceSnapshot(
			UUID.randomUUID(), runId, blockId, 0, SourceProvider.GITHUB, "pull_request", "PR 1", "PR 1",
			"Evidence body", "PRIVATE SNAPSHOT EXCERPT", "https://github.test/acme/repo/pull/1", null, null, "hash", Instant.now(),
		)
		val state = workflow.start(runId, listOf(evidence), null)
		admissionPersistence.createRun(ArtifactWorkflowRunReservation(
			devContext.devWorkspaceId, devContext.devUserId, null, "pack-${UUID.randomUUID()}", "fingerprint-${UUID.randomUUID()}",
			state, "OPENAI", "scripted", "{\"maxModelCalls\":12,\"maxTotalTokens\":1000,\"maxRunDurationMillis\":60000}",
		))
		val gateway = PublicPackGateway(evidence.id)
		ArtifactWorkflowRunWorker(executionPersistence, queryPersistence, workflow, gateway, workerId = "public-changelog-test").drain()
		val variantId = jdbcTemplate.queryForObject(
			"select cv.id from content_packs cp join content_variants cv on cv.content_pack_id = cp.id where cp.generation_run_id = ?",
			UUID::class.java,
			runId,
		)!!
		return PublicFixture(variantId)
	}
}

private data class PublicFixture(val variantId: UUID)

private class PublicPackGateway(private val evidenceId: UUID) : ArtifactWorkflowModelGateway {
	private lateinit var sentenceIds: List<UUID>
	override fun write(request: WriterModelRequest) = result(WriterOutput(listOf(WriterSentence("Supported sentence."), WriterSentence("Stable sentence."))))
	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> {
		sentenceIds = request.sentences.map { it.id }
		return result(ReviewerOutput(listOf(
			SentenceReview(sentenceIds[0], ReviewVerdict.SUPPORTED, listOf(evidenceId)),
			SentenceReview(sentenceIds[1], ReviewVerdict.NOT_REQUIRED),
		)))
	}
	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> = error("Unexpected rewrite")
	private fun <T : Any> result(value: T) = ModelCallResult(value, ModelCallMetadata(null, "scripted", "stop", 1, 1, 2, Duration.ofMillis(1), emptyMap()))
}
