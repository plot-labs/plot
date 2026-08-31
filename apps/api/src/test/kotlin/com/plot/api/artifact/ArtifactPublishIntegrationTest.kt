package com.plot.api.artifact

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArtifactPublishIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var admissionPersistence: ArtifactWorkflowAdmissionPersistence
	@Autowired private lateinit var executionPersistence: ArtifactWorkflowExecutionPersistence
	@Autowired private lateinit var queryPersistence: ArtifactWorkflowQueryPersistence
	@Autowired private lateinit var workflow: ArtifactWorkflowService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var objectMapper: ObjectMapper

	@BeforeEach
	@AfterEach
	fun restoreWritableWorkspace() {
		jdbcTemplate.update("delete from published_changelog_entries where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update(
			"""
			update workspaces
			set plan = 'founding',
			    entitlement_status = 'active',
			    access_mode = 'full',
			    trial_ends_at = now() + interval '30 days'
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun `publish requires citation confirmation and stores immutable snapshot`() {
		val fixture = readyPack()
		mockMvc.patch("/api/artifact-variants/${fixture.variantId}/sentences/${fixture.firstSentenceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"body":"User revised sentence."}"""
		}.andExpect { status { isOk() } }

		mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":2,"acknowledgeUnresolved":false}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("PUBLISH_CONFIRMATION_REQUIRED") }
			jsonPath("$.details.warnings[0].sentenceNumber") { value(1) }
		}

		val published = mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 2,
				"acknowledgeUnresolved" to true,
				"acknowledgedRevisionIds" to jdbcTemplate.queryForList(
					"select id from content_variant_sentence_revisions where sentence_id = ? and is_current",
					UUID::class.java,
					fixture.firstSentenceId,
				),
			))
		}.andExpect {
			status { isOk() }
			jsonPath("$.entrySlug") { exists() }
			jsonPath("$.publicPath") { value(org.hamcrest.Matchers.startsWith("/changelog/dev-workspace/")) }
			jsonPath("$.publishedAt") { exists() }
		}.andReturn().response.contentAsString
		val entrySlug = objectMapper.readTree(published).path("entrySlug").stringValue()
		val snapshotBeforeEdit = mockMvc.get("/api/public/changelog/dev-workspace/$entrySlug").andExpect {
			status { isOk() }
		}.andReturn().response.contentAsString

		mockMvc.patch("/api/artifact-variants/${fixture.variantId}/sentences/${fixture.firstSentenceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":2,"body":"Changed after publish."}"""
		}.andExpect { status { isOk() } }

		val snapshotAfterEdit = mockMvc.get("/api/public/changelog/dev-workspace/$entrySlug").andExpect {
			status { isOk() }
		}.andReturn().response.contentAsString
		assertEquals(
			objectMapper.readTree(snapshotBeforeEdit).path("bodyMarkdown").stringValue(),
			objectMapper.readTree(snapshotAfterEdit).path("bodyMarkdown").stringValue(),
		)
		assertTrue(snapshotAfterEdit.contains("User revised sentence."))
		assertFalse(snapshotAfterEdit.contains("Changed after publish."))
	}

	@Test
	fun `read only workspace cannot publish`() {
		val fixture = readyPack()
		jdbcTemplate.update(
			"update workspaces set entitlement_status = 'revoked', access_mode = 'read_only' where id = ?",
			devContext.devWorkspaceId,
		)
		mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
		}.andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("WORKSPACE_READ_ONLY") }
		}
	}

	@Test
	fun `duplicate variant publish returns conflict`() {
		val fixture = readyPack()
		publish(fixture.variantId, expectedRevisionNumber = 1)
		mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("PUBLISH_VARIANT_CONFLICT") }
		}
	}

	@Test
	fun `duplicate release tag publish returns conflict`() {
		val fixture = readyPack()
		val releaseRequestId = UUID.randomUUID()
		val deliveryId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId, devContext.devWorkspaceId, "installation-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/repo', 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId, devContext.devWorkspaceId, namespaceId, "repository-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into github_webhook_deliveries (
			  id, external_delivery_id, event_type, payload_hash, disposition, received_at
			) values (?, ?, 'release', ?, 'QUEUED', now())
			""".trimIndent(),
			deliveryId,
			"delivery-$deliveryId",
			"a".repeat(64),
		)
		jdbcTemplate.update(
			"""
			insert into github_release_draft_requests (
			  id, workspace_id, source_scope_id, initial_delivery_id, tag_name, status,
			  attempt_count, generation_attempt, transition_version, created_at, updated_at
			) values (?, ?, ?, ?, 'v9.9.9', 'READY', 0, 0, 0, now(), now())
			""".trimIndent(),
			releaseRequestId,
			devContext.devWorkspaceId,
			scopeId,
			deliveryId,
		)
		jdbcTemplate.update(
			"update content_packs set release_request_id = ? where id = ?",
			releaseRequestId,
			fixture.packId,
		)

		publish(fixture.variantId, expectedRevisionNumber = 1)
		mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("PUBLISH_TAG_CONFLICT") }
		}
	}

	private fun publish(variantId: UUID, expectedRevisionNumber: Int) {
		mockMvc.post("/api/artifact-variants/$variantId/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":$expectedRevisionNumber,"acknowledgeUnresolved":false}"""
		}.andExpect { status { isOk() } }
	}

	private fun readyPack(): PublishFixture {
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
		val gateway = PublishPackGateway(evidence.id)
		ArtifactWorkflowRunWorker(executionPersistence, queryPersistence, workflow, gateway, workerId = "publish-test").drain()
		val row = jdbcTemplate.queryForMap(
			"""
			select cp.id pack_id, cv.id variant_id from content_packs cp join content_variants cv on cv.content_pack_id=cp.id
			where cp.generation_run_id = ?
			""".trimIndent(), runId,
		)
		val sentenceIds = jdbcTemplate.query(
			"select id from content_variant_sentences where generation_run_id = ? order by order_index",
			{ rs, _ -> rs.getObject(1, UUID::class.java) }, runId,
		)
		return PublishFixture(runId, row["pack_id"] as UUID, row["variant_id"] as UUID, sentenceIds.first(), sentenceIds[1])
	}
}

private data class PublishFixture(val runId: UUID, val packId: UUID, val variantId: UUID, val firstSentenceId: UUID, val secondSentenceId: UUID)

private class PublishPackGateway(private val evidenceId: UUID) : ArtifactWorkflowModelGateway {
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
