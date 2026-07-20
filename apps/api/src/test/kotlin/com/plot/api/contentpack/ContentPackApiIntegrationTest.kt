package com.plot.api.contentpack

import com.plot.api.TestcontainersConfiguration
import com.plot.api.ai.provider.GenerationModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelCallResult
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.dev.DevContext
import com.plot.api.generation.GenerationPersistence
import com.plot.api.generation.GenerationRunReservation
import com.plot.api.generation.GenerationRunWorker
import com.plot.api.generation.GenerationWorkflowService
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.SentenceReview
import com.plot.api.generation.model.SourceProvider
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.WriterOutput
import com.plot.api.generation.model.WriterSentence
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ContentPackApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var persistence: GenerationPersistence
	@Autowired private lateinit var workflow: GenerationWorkflowService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var objectMapper: ObjectMapper

	@Test
	fun `sentence-local edit stales citations and acknowledged exports share private-safe markdown`() {
		val fixture = readyPack()
		mockMvc.get("/api/content-packs?page=0&size=25").andExpect {
			status { isOk() }
			jsonPath("$.items[0].id") { value(fixture.packId.toString()) }
			jsonPath("$.totalItems") { value(1) }
		}

		mockMvc.get("/api/content-packs/${fixture.packId}").andExpect {
			status { isOk() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.variant.sentences[0].verdict") { value("SUPPORTED") }
			jsonPath("$.variant.sentences[0].citations[0].snapshotExcerpt") { value("PRIVATE SNAPSHOT EXCERPT") }
		}

		mockMvc.patch("/api/content-variants/${fixture.variantId}/sentences/${fixture.firstSentenceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"body":"User revised sentence."}"""
		}.andExpect {
			status { isOk() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.variant.sentences[0].origin") { value("USER_MODIFIED") }
			jsonPath("$.variant.sentences[0].verdict") { value("USER_MODIFIED") }
			jsonPath("$.variant.sentences[0].citations[0].status") { value("STALE") }
			jsonPath("$.variant.sentences[1].body") { value("Stable sentence.") }
		}

		mockMvc.patch("/api/content-variants/${fixture.variantId}/sentences/${fixture.firstSentenceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"body":"Stale update."}"""
		}.andExpect {
			status { isConflict() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.error") { value("STALE_SENTENCE_REVISION") }
		}

		mockMvc.post("/api/content-variants/${fixture.variantId}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"acknowledgeUnresolved":false,"disposition":"COPY"}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("EXPORT_CONFIRMATION_REQUIRED") }
			jsonPath("$.details.sentenceIds[0]") { value(fixture.firstSentenceId.toString()) }
			jsonPath("$.details.revisionIds.length()") { value(1) }
		}
		val acknowledgedRevision = jdbcTemplate.queryForObject(
			"select id from content_variant_sentence_revisions where sentence_id = ? and is_current",
			UUID::class.java, fixture.firstSentenceId,
		)!!
		mockMvc.patch("/api/content-variants/${fixture.variantId}/sentences/${fixture.firstSentenceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":2,"body":"Changed after warning."}"""
		}.andExpect { status { isOk() } }
		mockMvc.post("/api/content-variants/${fixture.variantId}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"acknowledgeUnresolved" to true,
				"acknowledgedRevisionIds" to listOf(acknowledgedRevision),
				"disposition" to "COPY",
			))
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("EXPORT_CONFIRMATION_REQUIRED") }
		}

		val copy = export(fixture.variantId, "COPY")
		val download = export(fixture.variantId, "DOWNLOAD")
		assertEquals(copy, download)
		kotlin.test.assertFalse(copy.contains("PRIVATE SNAPSHOT EXCERPT"))
		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from generation_export_events where generation_run_id = ? and status = 'SUCCEEDED'",
			Int::class.java, fixture.runId,
		))
		assertEquals(setOf("COPY", "DOWNLOAD"), jdbcTemplate.queryForList(
			"select disposition from generation_export_events where generation_run_id = ? and status = 'SUCCEEDED'",
			String::class.java, fixture.runId,
		).toSet())
	}

	@Test
	fun `current revision without reviewer result is review-failed when the run failed`() {
		val fixture = readyPack()
		val currentRevisionId = jdbcTemplate.queryForObject(
			"select id from content_variant_sentence_revisions where sentence_id = ? and is_current",
			UUID::class.java,
			fixture.firstSentenceId,
		)!!
		jdbcTemplate.update(
			"update content_variant_sentence_revisions set is_current = false where id = ?",
			currentRevisionId,
		)
		jdbcTemplate.update(
			"""
			insert into content_variant_sentence_revisions (
				id, workspace_id, generation_run_id, content_variant_id, sentence_id,
				revision_no, origin, body, is_current, created_at
			) values (?, ?, ?, ?, ?, 2, 'REWRITTEN', 'Latest unreviewed rewrite.', true, now())
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			fixture.runId,
			fixture.variantId,
			fixture.firstSentenceId,
		)
		jdbcTemplate.update(
			"update generation_runs set status = 'NEEDS_REVIEW', error_code = 'MALFORMED_OUTPUT' where id = ?",
			fixture.runId,
		)

		mockMvc.get("/api/content-packs/${fixture.packId}").andExpect {
			status { isOk() }
			jsonPath("$.variant.sentences[0].verdict") { value("REVIEW_FAILED") }
			jsonPath("$.variant.sentences[0].reason") { value("MALFORMED_OUTPUT") }
		}
	}

	private fun export(variantId: UUID, disposition: String): String {
		val revisionIds = jdbcTemplate.queryForList(
			"select id from content_variant_sentence_revisions where content_variant_id = ? and is_current and origin = 'USER_MODIFIED'",
			UUID::class.java, variantId,
		)
		val response = mockMvc.post("/api/content-variants/$variantId/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"acknowledgeUnresolved" to true,
				"acknowledgedRevisionIds" to revisionIds,
				"disposition" to disposition,
			))
		}.andExpect {
			status { isOk() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.warningAcknowledged") { value(true) }
		}.andReturn().response.contentAsString
		return objectMapper.readTree(response).get("text").stringValue()
	}

	private fun readyPack(): Fixture {
		val runId = UUID.randomUUID()
		val blockId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into writing_blocks (id, workspace_id, source_origin, source_kind, title, body, url,
			 content_hash, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'github', 'pull_request', 'PR', 'evidence', 'https://github.test/acme/repo/pull/1',
			 'block-hash', now(), 'ACTIVE', ?, now(), now())
			""".trimIndent(), blockId, devContext.devWorkspaceId, devContext.devUserId,
		)
		val evidence = EvidenceSnapshot(
			UUID.randomUUID(), runId, blockId, 0, SourceProvider.GITHUB, "pull_request", "PR 1", "PR 1",
			"Evidence body", "PRIVATE SNAPSHOT EXCERPT", "https://github.test/acme/repo/pull/1", null, null, "hash", Instant.now(),
		)
		val state = workflow.start(runId, listOf(evidence), null)
		persistence.createRun(GenerationRunReservation(
			devContext.devWorkspaceId, devContext.devUserId, null, "pack-${UUID.randomUUID()}", "fingerprint-${UUID.randomUUID()}",
			state, "OPENAI", "scripted", "{\"maxModelCalls\":12,\"maxTotalTokens\":1000,\"maxRunDurationMillis\":60000}",
		))
		val gateway = PackGateway(evidence.id)
		GenerationRunWorker(persistence, workflow, gateway, workerId = "content-pack-test").drain()
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
		return Fixture(runId, row["pack_id"] as UUID, row["variant_id"] as UUID, sentenceIds.first())
	}
}

private data class Fixture(val runId: UUID, val packId: UUID, val variantId: UUID, val firstSentenceId: UUID)

private class PackGateway(private val evidenceId: UUID) : GenerationModelGateway {
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
