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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
import tools.jackson.databind.JsonNode
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
				jsonPath("$.totalItems") { value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)) }
		}

		val initial = mockMvc.get("/api/content-packs/${fixture.packId}").andExpect {
			status { isOk() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.variant.revisionNumber") { value(1) }
			jsonPath("$.variant.revisionId") { exists() }
			jsonPath("$.variant.sources.length()") { value(1) }
			jsonPath("$.variant.sentences[0].body") { value("Supported sentence.") }
			jsonPath("$.variant.sentences[0].citations[0].sourceLabel") { value("PR 1") }
			jsonPath("$.variant.sentences[0].citations[0].snapshotExcerpt") { doesNotExist() }
		}.andReturn().response.contentAsString
		assertFalse(initial.contains("\"verdict\""))
		assertFalse(initial.contains("PRIVATE SNAPSHOT EXCERPT"))
		assertFalse(initial.contains("\"sourceAccess\""))

		mockMvc.patch("/api/content-variants/${fixture.variantId}/sentences/${fixture.firstSentenceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"body":"User revised sentence."}"""
		}.andExpect {
			status { isOk() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.variant.revisionNumber") { value(2) }
			jsonPath("$.variant.sentences[0].origin") { value("USER_MODIFIED") }
			jsonPath("$.variant.sentences[0].revisionNumber") { value(2) }
			jsonPath("$.variant.lexicalContent.root.children[0].children[0].text") { value("User revised sentence.") }
			jsonPath("$.variant.sentences[0].citations.length()") { value(0) }
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

		val rejected = mockMvc.post("/api/content-variants/${fixture.variantId}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":2,"includeSources":false,"acknowledgeUnresolved":false,"disposition":"COPY"}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("EXPORT_CONFIRMATION_REQUIRED") }
			jsonPath("$.details.warnings[0].sentenceNumber") { value(1) }
			jsonPath("$.details.warnings[0].excerpt") { value("User revised sentence.") }
		}.andReturn().response.contentAsString
		assertFalse(rejected.contains(fixture.firstSentenceId.toString()))
		assertFalse(rejected.contains("revisionIds"))
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
				"expectedRevisionNumber" to 3,
				"includeSources" to false,
				"acknowledgeUnresolved" to true,
				"acknowledgedRevisionIds" to listOf(acknowledgedRevision),
				"disposition" to "COPY",
			))
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("EXPORT_CONFIRMATION_REQUIRED") }
		}

		val copy = export(fixture.variantId, "COPY", includeSources = false)
		val download = export(fixture.variantId, "DOWNLOAD", includeSources = false)
		export(fixture.variantId, "COPY", includeSources = true)
		assertEquals(copy, download)
		kotlin.test.assertFalse(copy.contains("PRIVATE SNAPSHOT EXCERPT"))
		assertFalse(copy.contains("[1]"))
		assertFalse(copy.contains("## Sources"))
		assertEquals(3, jdbcTemplate.queryForObject(
			"select count(*) from generation_export_events where generation_run_id = ? and status = 'SUCCEEDED'",
			Int::class.java, fixture.runId,
		))
		assertEquals(2, jdbcTemplate.queryForList(
			"select distinct export_input_hash from generation_export_events where generation_run_id = ? and status = 'SUCCEEDED'",
			String::class.java, fixture.runId,
		).size)
		assertEquals(setOf("COPY", "DOWNLOAD"), jdbcTemplate.queryForList(
			"select disposition from generation_export_events where generation_run_id = ? and status = 'SUCCEEDED'",
			String::class.java, fixture.runId,
		).toSet())
	}

	@Test
	fun `export requires explicit source choice and edits after delivery do not auto redeliver`() {
		val fixture = readyPack()
		val withoutSources = export(fixture.variantId, "COPY", includeSources = false, expectWarningAcknowledged = false)
		assertFalse(withoutSources.contains("## Sources"))
		val withSources = export(fixture.variantId, "COPY", includeSources = true, expectWarningAcknowledged = false)
		assertTrue(withSources.contains("## Sources"))
		assertEquals(setOf(false, true), jdbcTemplate.queryForList(
			"select include_sources from generation_export_events where generation_run_id = ? and status = 'SUCCEEDED'",
			Boolean::class.java, fixture.runId,
		).toSet())
		assertEquals(2, jdbcTemplate.queryForList(
			"select distinct export_input_hash from generation_export_events where generation_run_id = ? and status = 'SUCCEEDED'",
			String::class.java, fixture.runId,
		).size)

		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to lexicalContent("Delivered edit.", "Stable sentence."),
				"statements" to listOf(
					mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Delivered edit."),
					mapOf("id" to fixture.secondSentenceId, "orderIndex" to 1, "body" to "Stable sentence."),
				),
			))
		}.andExpect { status { isOk() } }
		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from generation_export_events where generation_run_id = ? and status = 'SUCCEEDED'",
			Int::class.java, fixture.runId,
		))

		mockMvc.post("/api/content-variants/${fixture.variantId}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":2,"acknowledgeUnresolved":false,"disposition":"COPY"}"""
		}.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `whole artifact save preserves stable sentence ids and rejects stale tabs`() {
		val fixture = readyPack()
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to lexicalContent("Latest unreviewed rewrite.", "Stable sentence."),
				"statements" to listOf(
					mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Latest unreviewed rewrite."),
					mapOf("id" to fixture.secondSentenceId, "orderIndex" to 1, "body" to "Stable sentence."),
				),
			))
		}.andExpect {
			status { isOk() }
			jsonPath("$.variant.revisionNumber") { value(2) }
			jsonPath("$.variant.sentences[0].id") { value(fixture.firstSentenceId.toString()) }
			jsonPath("$.variant.sentences[0].revisionNumber") { value(2) }
			jsonPath("$.variant.sentences[0].body") { value("Latest unreviewed rewrite.") }
			jsonPath("$.variant.sentences[1].id") { value(fixture.secondSentenceId.toString()) }
			jsonPath("$.variant.sentences[1].revisionNumber") { value(1) }
			jsonPath("$.variant.sources.length()") { value(0) }
		}
		assertEquals(listOf("STALE", "STATEMENT_CHANGED"), jdbcTemplate.queryForObject(
			"select status, stale_reason from sentence_citations where sentence_id = ? and status = 'STALE' limit 1",
			{ rs, _ -> listOf(rs.getString(1), rs.getString(2)) },
			fixture.firstSentenceId,
		))

		val latest = mockMvc.get("/api/content-variants/${fixture.variantId}").andExpect {
			status { isOk() }
			jsonPath("$.variant.sentences[0].body") { value("Latest unreviewed rewrite.") }
		}.andReturn().response.contentAsString
		assertFalse(latest.contains("\"verdict\""))
		assertFalse(latest.contains("\"reason\""))
		mockMvc.post("/api/content-variants/${fixture.variantId}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"includeSources":false,"acknowledgeUnresolved":true,"disposition":"COPY"}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("STALE_ARTIFACT_REVISION") }
		}

		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to lexicalContent(),
				"statements" to emptyList<Any>(),
			))
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("STALE_ARTIFACT_REVISION") }
		}

		val rejected = mockMvc.post("/api/content-variants/${fixture.variantId}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":2,"includeSources":false,"acknowledgeUnresolved":false,"disposition":"COPY"}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("EXPORT_CONFIRMATION_REQUIRED") }
			jsonPath("$.details.warnings[0].sentenceNumber") { value(1) }
			jsonPath("$.details.warnings[0].excerpt") { value("Latest unreviewed rewrite.") }
		}.andReturn().response.contentAsString
		assertFalse(rejected.contains(fixture.firstSentenceId.toString()))
		assertTrue(export(fixture.variantId, "COPY", includeSources = false).contains("Latest unreviewed rewrite."))
	}

	@Test
	fun `whole artifact save preserves ids through reorder and stales only deleted evidence`() {
		val fixture = readyPack()
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to lexicalContent("Stable sentence.", "Supported sentence."),
				"statements" to listOf(
					mapOf("id" to fixture.secondSentenceId, "orderIndex" to 0, "body" to "Stable sentence."),
					mapOf("id" to fixture.firstSentenceId, "orderIndex" to 1, "body" to "Supported sentence."),
				),
			))
		}.andExpect {
			status { isOk() }
			jsonPath("$.variant.sentences[0].id") { value(fixture.secondSentenceId.toString()) }
			jsonPath("$.variant.sentences[1].id") { value(fixture.firstSentenceId.toString()) }
			jsonPath("$.variant.sources[0].statementIds[0]") { value(fixture.firstSentenceId.toString()) }
			jsonPath("$.variant.sentences[0].revisionNumber") { value(1) }
			jsonPath("$.variant.sentences[1].revisionNumber") { value(1) }
		}

		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 2,
				"lexicalContent" to lexicalContent("Stable sentence."),
				"statements" to listOf(mapOf("id" to fixture.secondSentenceId, "orderIndex" to 0, "body" to "Stable sentence.")),
			))
		}.andExpect {
			status { isOk() }
			jsonPath("$.variant.sentences[0].id") { value(fixture.secondSentenceId.toString()) }
			jsonPath("$.variant.sources.length()") { value(0) }
		}
		assertEquals(listOf("REMOVED", "STATEMENT_REMOVED"), jdbcTemplate.queryForObject(
			"select status, stale_reason from sentence_citations where sentence_id = ? limit 1",
			{ rs, _ -> listOf(rs.getString(1), rs.getString(2)) },
			fixture.firstSentenceId,
		))
	}

	@Test
	fun `whole artifact save rejects malformed or mismatched Lexical payloads and nested invalid statements`() {
		val fixture = readyPack()
		val malformed = objectMapper.writeValueAsString(mapOf(
			"expectedRevisionNumber" to 1,
			"lexicalContent" to mapOf("root" to mapOf("children" to emptyList<Any>())),
			"statements" to listOf(mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Supported sentence.")),
		))
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = malformed
		}.andExpect { status { isBadRequest() } }

		val mismatched = objectMapper.writeValueAsString(mapOf(
			"expectedRevisionNumber" to 1,
			"lexicalContent" to lexicalContent("Different body."),
			"statements" to listOf(mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Supported sentence.")),
		))
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = mismatched
		}.andExpect { status { isBadRequest() } }

		val nodeKey = objectMapper.readTree(objectMapper.writeValueAsString(lexicalContent("Supported sentence.")))
			.apply { get("root").asObject().put("key", "root-key") }
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to nodeKey,
				"statements" to listOf(mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Supported sentence.")),
			))
		}.andExpect { status { isBadRequest() } }

		val unknownType = objectMapper.readTree(objectMapper.writeValueAsString(lexicalContent("Supported sentence.")))
			.apply { get("root").get("children")[0].asObject().put("type", "heading") }
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to unknownType,
				"statements" to listOf(mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Supported sentence.")),
			))
		}.andExpect { status { isBadRequest() } }

		val unknownField = objectMapper.readTree(objectMapper.writeValueAsString(lexicalContent("Supported sentence.")))
			.apply { get("root").get("children")[0].get("children")[0].asObject().put("key", "text-key") }
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to unknownField,
				"statements" to listOf(mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Supported sentence.")),
			))
		}.andExpect { status { isBadRequest() } }

		val negativeOrder = objectMapper.writeValueAsString(mapOf(
			"expectedRevisionNumber" to 1,
			"lexicalContent" to lexicalContent("Supported sentence."),
			"statements" to listOf(mapOf("id" to fixture.firstSentenceId, "orderIndex" to -1, "body" to "Supported sentence.")),
		))
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = negativeOrder
		}.andExpect { status { isBadRequest() } }
		mockMvc.post("/api/content-variants/${fixture.variantId}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":true,"disposition":"COPY"}"""
		}.andExpect { status { isBadRequest() } }
		mockMvc.post("/api/content-variants/${fixture.variantId}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false,"disposition":"COPY"}"""
		}.andExpect { status { isBadRequest() } }

		assertEquals(1, jdbcTemplate.queryForObject(
			"select revision_no from content_variant_revisions where content_variant_id = ? and is_current",
			Int::class.java,
			fixture.variantId,
		))
	}

	@Test
	fun `Lexical validation rejects fractional values and wrong node versions`() {
		val fixture = readyPack()
		val fractional = objectMapper.readTree(objectMapper.writeValueAsString(lexicalContent("Supported sentence.")))
			.apply { get("root").get("children")[0].get("children")[0].asObject().put("detail", 0.5) }
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to fractional,
				"statements" to listOf(mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Supported sentence.")),
			))
		}.andExpect { status { isBadRequest() } }

		val wrongRootVersion = objectMapper.readTree(objectMapper.writeValueAsString(lexicalContent("Supported sentence.")))
			.apply { get("root").asObject().put("version", 2) }
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to wrongRootVersion,
				"statements" to listOf(mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Supported sentence.")),
			))
		}.andExpect { status { isBadRequest() } }

		val wrongTextVersion = objectMapper.readTree(objectMapper.writeValueAsString(lexicalContent("Supported sentence.")))
			.apply { get("root").get("children")[0].get("children")[0].asObject().put("version", 2) }
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to wrongTextVersion,
				"statements" to listOf(mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Supported sentence.")),
			))
		}.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `Lexical validation accepts linebreaks and rejects malformed linebreak fields`() {
		val fixture = readyPack()
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to lexicalContentWithLinebreak(),
				"statements" to listOf(mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "First\nSecond")),
			))
		}.andExpect {
			status { isOk() }
			jsonPath("$.variant.revisionNumber") { value(2) }
			jsonPath("$.variant.lexicalContent.root.children[0].children[1].type") { value("linebreak") }
			jsonPath("$.variant.lexicalContent.root.children[0].children[1].version") { value(1) }
		}

		val unknownFieldFixture = readyPack()
		mockMvc.patch("/api/content-variants/${unknownFieldFixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to lexicalContentWithLinebreak(unknownField = true),
				"statements" to listOf(mapOf("id" to unknownFieldFixture.firstSentenceId, "orderIndex" to 0, "body" to "First\nSecond")),
			))
		}.andExpect { status { isBadRequest() } }

		val wrongVersionFixture = readyPack()
		mockMvc.patch("/api/content-variants/${wrongVersionFixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 1,
				"lexicalContent" to lexicalContentWithLinebreak(linebreakVersion = 2),
				"statements" to listOf(mapOf("id" to wrongVersionFixture.firstSentenceId, "orderIndex" to 0, "body" to "First\nSecond")),
			))
		}.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `legacy sentence edit emits canonical Lexical JSON accepted by whole artifact save`() {
		val fixture = readyPack()
		val edited = mockMvc.patch("/api/content-variants/${fixture.variantId}/sentences/${fixture.firstSentenceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"body":"Legacy edited sentence."}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.variant.revisionNumber") { value(2) }
			jsonPath("$.variant.lexicalContent.root.type") { value("root") }
			jsonPath("$.variant.lexicalContent.root.children[0].type") { value("paragraph") }
			jsonPath("$.variant.lexicalContent.root.children[0].children[0].detail") { value(0) }
		}.andReturn().response.contentAsString
		val editedTree = objectMapper.readTree(edited)
		val canonicalLexicalContent = editedTree.get("variant").get("lexicalContent")
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to 2,
				"lexicalContent" to canonicalLexicalContent,
				"statements" to listOf(
					mapOf("id" to fixture.firstSentenceId, "orderIndex" to 0, "body" to "Legacy edited sentence."),
					mapOf("id" to fixture.secondSentenceId, "orderIndex" to 1, "body" to "Stable sentence."),
				),
			))
		}.andExpect {
			status { isOk() }
			jsonPath("$.variant.revisionNumber") { value(3) }
			jsonPath("$.variant.sentences[0].body") { value("Legacy edited sentence.") }
		}
	}

	@Test
	fun `empty whole artifact revision remains empty instead of falling back to legacy sentences`() {
		val fixture = readyPack()
		val emptySave = objectMapper.writeValueAsString(mapOf(
			"expectedRevisionNumber" to 1,
			"lexicalContent" to lexicalContent(),
			"statements" to emptyList<Any>(),
		))
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = emptySave
		}.andExpect {
			status { isOk() }
			jsonPath("$.variant.revisionNumber") { value(2) }
			jsonPath("$.variant.sentences.length()") { value(0) }
			jsonPath("$.variant.sources.length()") { value(0) }
		}
		mockMvc.patch("/api/content-variants/${fixture.variantId}") {
			contentType = MediaType.APPLICATION_JSON
			content = emptySave.replace("\"expectedRevisionNumber\":1", "\"expectedRevisionNumber\":2")
		}.andExpect {
			status { isOk() }
			jsonPath("$.variant.revisionNumber") { value(3) }
			jsonPath("$.variant.sentences.length()") { value(0) }
		}
	}

	@Test
	fun `lost source access is retained internally but omitted from public sources`() {
		val fixture = readyPack()
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'ORGANIZATION', ?, 'acme', 'ERROR', now(), now())
			""".trimIndent(),
			namespaceId, devContext.devWorkspaceId, "org:${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, external_key, display_name, url, status, status_reason, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', '9001', 'acme/plot', 'acme/plot', 'https://github.com/acme/plot', 'ERROR', 'GRANT_REMOVED', now(), now())
			""".trimIndent(),
			scopeId, devContext.devWorkspaceId, namespaceId,
		)
		jdbcTemplate.update(
			"update generation_runs set source_scope_id = ? where workspace_id = ? and id = ?",
			scopeId, devContext.devWorkspaceId, fixture.runId,
		)

		mockMvc.get("/api/content-packs/${fixture.packId}").andExpect {
			status { isOk() }
			jsonPath("$.variant.sources.length()") { value(0) }
			jsonPath("$.variant.sentences[0].citations.length()") { value(0) }
		}
	}

	@Test
	fun `unsafe source URL is omitted from public sources`() {
		val fixture = readyPack("javascript:alert(1)")
		mockMvc.get("/api/content-packs/${fixture.packId}").andExpect {
			status { isOk() }
			jsonPath("$.variant.sources.length()") { value(0) }
			jsonPath("$.variant.sentences[0].citations.length()") { value(0) }
		}
	}

	private fun export(variantId: UUID, disposition: String, includeSources: Boolean, expectWarningAcknowledged: Boolean = true): String {
		val revisionIds = jdbcTemplate.queryForList(
			"select id from content_variant_sentence_revisions where content_variant_id = ? and is_current and origin = 'USER_MODIFIED'",
			UUID::class.java, variantId,
		)
		val response = mockMvc.post("/api/content-variants/$variantId/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = objectMapper.writeValueAsString(mapOf(
				"expectedRevisionNumber" to jdbcTemplate.queryForObject(
					"select revision_no from content_variant_revisions where content_variant_id = ? and is_current",
					Int::class.java,
					variantId,
				),
				"includeSources" to includeSources,
				"acknowledgeUnresolved" to true,
				"acknowledgedRevisionIds" to revisionIds,
				"disposition" to disposition,
			))
		}.andExpect {
			status { isOk() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.warningAcknowledged") { value(expectWarningAcknowledged) }
		}.andReturn().response.contentAsString
		return objectMapper.readTree(response).get("text").stringValue()
	}

	private fun lexicalContent(vararg bodies: String): Map<String, Any> = mapOf(
		"root" to mapOf(
			"children" to bodies.map { body ->
				mapOf(
					"children" to listOf(mapOf(
						"detail" to 0,
						"format" to 0,
						"mode" to "normal",
						"style" to "",
						"text" to body,
						"type" to "text",
						"version" to 1,
					)),
					"direction" to null,
					"format" to "",
					"indent" to 0,
					"type" to "paragraph",
					"version" to 1,
				)
			},
			"direction" to null,
			"format" to "",
			"indent" to 0,
			"type" to "root",
			"version" to 1,
		),
	)

	private fun lexicalContentWithLinebreak(linebreakVersion: Int = 1, unknownField: Boolean = false): JsonNode {
		val content = objectMapper.readTree(objectMapper.writeValueAsString(lexicalContent("First\nSecond")))
		val children = content.get("root").get("children")[0].asObject().putArray("children")
		children.add(objectMapper.createObjectNode().apply {
			put("detail", 0)
			put("format", 0)
			put("mode", "normal")
			put("style", "")
			put("text", "First")
			put("type", "text")
			put("version", 1)
		})
		children.add(objectMapper.createObjectNode().apply {
			put("type", "linebreak")
			put("version", linebreakVersion)
			if (unknownField) put("key", "linebreak-key")
		})
		children.add(objectMapper.createObjectNode().apply {
			put("detail", 0)
			put("format", 0)
			put("mode", "normal")
			put("style", "")
			put("text", "Second")
			put("type", "text")
			put("version", 1)
		})
		return content
	}

	private fun readyPack(sourceUrl: String = "https://github.test/acme/repo/pull/1"): Fixture {
		val runId = UUID.randomUUID()
		val blockId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into writing_blocks (id, workspace_id, source_origin, source_kind, title, body, url,
			 content_hash, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'github', 'pull_request', 'PR', 'evidence', ?,
			 'block-hash', now(), 'ACTIVE', ?, now(), now())
			""".trimIndent(), blockId, devContext.devWorkspaceId, sourceUrl, devContext.devUserId,
		)
		val evidence = EvidenceSnapshot(
			UUID.randomUUID(), runId, blockId, 0, SourceProvider.GITHUB, "pull_request", "PR 1", "PR 1",
			"Evidence body", "PRIVATE SNAPSHOT EXCERPT", sourceUrl, null, null, "hash", Instant.now(),
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
		return Fixture(runId, row["pack_id"] as UUID, row["variant_id"] as UUID, sentenceIds.first(), sentenceIds[1])
	}
}

private data class Fixture(val runId: UUID, val packId: UUID, val variantId: UUID, val firstSentenceId: UUID, val secondSentenceId: UUID)

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
