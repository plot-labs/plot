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
		jdbcTemplate.execute("alter table product_delivery_events disable trigger product_delivery_events_append_only")
		jdbcTemplate.update(
			"delete from product_delivery_events where workspace_id = ?",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.execute("alter table product_delivery_events enable trigger product_delivery_events_append_only")
		jdbcTemplate.update(
			"delete from published_changelog_entry_citations where workspace_id = ?",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update(
			"delete from published_changelog_entry_sentences where workspace_id = ?",
			devContext.devWorkspaceId,
		)
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
			jsonPath("$.publicPath") { value(org.hamcrest.Matchers.startsWith("/dev-workspace/changelog/")) }
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
	fun `unpublish keeps the internal snapshot and stops public list body and citations`() {
		val fixture = readyPack()
		val published = mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
		}.andExpect { status { isOk() } }.andReturn().response.contentAsString
		val publishedTree = objectMapper.readTree(published)
		val entrySlug = publishedTree.path("entrySlug").stringValue()

		mockMvc.get("/api/artifacts/${fixture.packId}").andExpect {
			status { isOk() }
			jsonPath("$.publication.entrySlug") { value(entrySlug) }
			jsonPath("$.publication.publicPath") { value("/dev-workspace/changelog/$entrySlug") }
		}

		mockMvc.post("/api/artifact-variants/${fixture.variantId}/unpublish").andExpect {
			status { isOk() }
			jsonPath("$.entrySlug") { value(entrySlug) }
			jsonPath("$.unpublishedAt") { exists() }
		}

		mockMvc.get("/api/public/changelog/dev-workspace").andExpect {
			status { isOk() }
			jsonPath("$.entries.length()") { value(0) }
		}
		mockMvc.get("/api/public/changelog/dev-workspace/$entrySlug").andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
		mockMvc.get("/api/artifacts/${fixture.packId}").andExpect {
			status { isOk() }
			jsonPath("$.publication") { value(null as Any?) }
		}

		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from published_changelog_entries where workspace_id = ? and unpublished_at is not null",
			Int::class.java,
			devContext.devWorkspaceId,
		))
		assertEquals(2, jdbcTemplate.queryForObject(
			"""
			select count(*) from published_changelog_entry_sentences s
			join published_changelog_entries e on e.id = s.published_changelog_entry_id
			where e.workspace_id = ? and e.unpublished_at is not null
			""".trimIndent(),
			Int::class.java,
			devContext.devWorkspaceId,
		))

		mockMvc.post("/api/artifact-variants/${fixture.variantId}/unpublish").andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("ENTRY_NOT_LIVE") }
		}
	}

	@Test
	fun `publish records a published delivery event and browser delivery events stay separate`() {
		val fixture = readyPack()
		val published = mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
		}.andExpect { status { isOk() } }.andReturn().response.contentAsString
		val entryId = objectMapper.readTree(published).path("entryId").textValue()

		assertEquals(1, jdbcTemplate.queryForObject(
			"""
			select count(*) from product_delivery_events
			where workspace_id = ? and kind = 'PUBLISHED' and published_changelog_entry_id = ?::uuid
			""".trimIndent(),
			Int::class.java,
			devContext.devWorkspaceId,
			entryId,
		))

		val export = mockMvc.post("/api/artifact-variants/${fixture.variantId}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"includeSources":false,"acknowledgeUnresolved":false,"disposition":"COPY"}"""
		}.andExpect { status { isOk() } }.andReturn().response.contentAsString
		val exportId = objectMapper.readTree(export).path("exportId").textValue()

		mockMvc.post("/api/artifact-variants/${fixture.variantId}/delivery-events") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"kind":"CLIPBOARD_WRITE_SUCCEEDED","exportId":"$exportId","clientEventId":"${UUID.randomUUID()}"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.kind") { value("CLIPBOARD_WRITE_SUCCEEDED") }
			jsonPath("$.duplicate") { value(false) }
		}

		mockMvc.post("/api/artifact-variants/${fixture.variantId}/delivery-events") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"kind":"CLIPBOARD_WRITE_SUCCEEDED","exportId":"$exportId","clientEventId":"${UUID.randomUUID()}"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.duplicate") { value(true) }
		}

		mockMvc.post("/api/artifact-variants/${fixture.variantId}/delivery-events") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"kind":"EXTERNAL_DELIVERY_CONFIRMED","entryId":"$entryId","clientEventId":"${UUID.randomUUID()}"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.kind") { value("EXTERNAL_DELIVERY_CONFIRMED") }
		}

		assertEquals(1, jdbcTemplate.queryForObject(
			"""
			select count(*) from product_delivery_events
			where workspace_id = ? and kind = 'CLIPBOARD_WRITE_SUCCEEDED' and generation_export_event_id = ?::uuid
			""".trimIndent(),
			Int::class.java,
			devContext.devWorkspaceId,
			exportId,
		))
	}

	@Test
	fun `expired trial can still unpublish a live changelog`() {
		val fixture = readyPack()
		mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
		}.andExpect { status { isOk() } }

		jdbcTemplate.update(
			"""
			update workspaces
			set plan = 'trial',
			    entitlement_status = 'expired',
			    access_mode = 'read_only',
			    trial_ends_at = now() - interval '1 day'
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)

		mockMvc.post("/api/artifact-variants/${fixture.variantId}/unpublish").andExpect {
			status { isOk() }
			jsonPath("$.unpublishedAt") { exists() }
		}
		mockMvc.get("/api/public/changelog/dev-workspace").andExpect {
			status { isOk() }
			jsonPath("$.entries.length()") { value(0) }
		}
	}

	@Test
	fun `trial pack limit still allows edit and first publish`() {
		val fixture = readyPack()
		val extraRunIds = mutableListOf<UUID>()
		val extraPackIds = mutableListOf<UUID>()
		try {
			jdbcTemplate.update(
				"""
				update workspaces
				set plan = 'trial',
				    entitlement_status = 'trialing',
				    access_mode = 'full',
				    trial_ends_at = now() + interval '30 days'
				where id = ?
				""".trimIndent(),
				devContext.devWorkspaceId,
			)
			repeat(2) {
				val runId = UUID.randomUUID()
				extraRunIds += runId
				jdbcTemplate.update(
					"""
					insert into generation_runs (
					  id, workspace_id, created_by_user_id, idempotency_key, request_fingerprint,
					  status, workflow_version, prompt_version, output_schema_version, budget_version,
					  provider, model_name, budget_snapshot, finished_at, created_at, updated_at
					) values (?, ?, ?, ?, ?, 'READY', 'test-v1', 'test-v1', 'test-v1', 'test-v1',
					  'TEST', 'test', '{}'::jsonb, now(), now(), now())
					""".trimIndent(),
					runId,
					devContext.devWorkspaceId,
					devContext.devUserId,
					"trial-$runId",
					"fingerprint-$runId",
				)
				val packId = UUID.randomUUID()
				extraPackIds += packId
				jdbcTemplate.update(
					"""
					insert into content_packs (
					  id, workspace_id, generation_run_id, title, status, created_at, updated_at
					) values (?, ?, ?, 'Trial pack', 'READY', now(), now())
					""".trimIndent(),
					packId,
					devContext.devWorkspaceId,
					runId,
				)
			}

			mockMvc.patch("/api/artifact-variants/${fixture.variantId}/sentences/${fixture.firstSentenceId}") {
				contentType = MediaType.APPLICATION_JSON
				content = """{"expectedRevisionNumber":1,"body":"Trial completion edit."}"""
			}.andExpect { status { isOk() } }
			mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
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
			}.andExpect { status { isOk() } }
			mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
				contentType = MediaType.APPLICATION_JSON
				content = """{"name":"Blocked"}"""
			}.andExpect {
				status { isForbidden() }
				jsonPath("$.error") { value("WORKSPACE_READ_ONLY") }
			}
		} finally {
			extraPackIds.forEach { jdbcTemplate.update("delete from content_packs where id = ?", it) }
			extraRunIds.forEach { jdbcTemplate.update("delete from generation_runs where id = ?", it) }
		}
	}

	@Test
	fun `elapsed trial cannot publish existing draft`() {
		val fixture = readyPack()
		jdbcTemplate.update(
			"""
			update workspaces
			set plan = 'trial',
			    entitlement_status = 'trialing',
			    access_mode = 'full',
			    trial_ends_at = now() - interval '1 second'
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
		mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
		}.andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("WORKSPACE_READ_ONLY") }
		}
		mockMvc.post("/api/artifact-variants/${fixture.variantId}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"includeSources":false,"acknowledgeUnresolved":false,"disposition":"COPY"}"""
		}.andExpect { status { isOk() } }
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
