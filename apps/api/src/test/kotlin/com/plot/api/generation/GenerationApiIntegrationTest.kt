package com.plot.api.generation

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.annotation.DirtiesContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import com.plot.api.ai.provider.GenerationModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelCallResult
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true", "server.address=127.0.0.1"])
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GenerationApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var objectMapper: ObjectMapper
	@Autowired private lateinit var persistence: GenerationPersistence
	@Autowired private lateinit var workflow: GenerationWorkflowService

	@Test
	fun `generation requires idempotency key and never caches errors`() {
		mockMvc.post("/api/generations") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"sourceScopeId":"00000000-0000-0000-0000-000000000001","writingBlockIds":["00000000-0000-0000-0000-000000000002"]}"""
		}.andExpect {
			status { isBadRequest() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
		mockMvc.post("/api/generations") {
			header("Idempotency-Key", "   ")
			contentType = MediaType.APPLICATION_JSON
			content = """{"sourceScopeId":"00000000-0000-0000-0000-000000000001","writingBlockIds":["00000000-0000-0000-0000-000000000002"]}"""
		}.andExpect {
			status { isBadRequest() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.error") { value("BAD_REQUEST") }
		}
	}

	@Test
	fun `create is idempotent scoped immutable and exposes only safe polling projection`() {
		val fixture = sourceFixture()
		val key = "api-${UUID.randomUUID()}"
		val body = """{"sourceScopeId":"${fixture.scopeId}","writingBlockIds":["${fixture.blockId}"],"instruction":"Release notes"}"""
		val first = mockMvc.post("/api/generations") {
			header("Idempotency-Key", key)
			contentType = MediaType.APPLICATION_JSON
			content = body
		}.andExpect {
			status { isAccepted() }
			header { exists("Location") }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.evidence[0].snapshotExcerpt") { value("ORIGINAL PRIVATE EVIDENCE") }
			jsonPath("$.evidence[0].snapshotBody") { doesNotExist() }
			jsonPath("$.modelName") { doesNotExist() }
			jsonPath("$.pollAfterMs") { value(500) }
		}.andReturn()
		val firstJson = objectMapper.readTree(first.response.contentAsString)
		val runId = UUID.fromString(firstJson.get("id").toString().trim('"'))

		val repeated = mockMvc.post("/api/generations") {
			header("Idempotency-Key", key)
			contentType = MediaType.APPLICATION_JSON
			content = body
		}.andExpect { status { isAccepted() } }.andReturn()
		assertEquals(runId.toString(), objectMapper.readTree(repeated.response.contentAsString).get("id").toString().trim('"'))

		mockMvc.post("/api/generations") {
			header("Idempotency-Key", key)
			contentType = MediaType.APPLICATION_JSON
			content = body.replace("Release notes", "Different option")
		}.andExpect {
			status { isConflict() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.error") { value("IDEMPOTENCY_KEY_REUSED") }
		}

		jdbcTemplate.update("update writing_blocks set body='MUTATED', status='ARCHIVED', updated_at=now() where id = ?", fixture.blockId)
		mockMvc.post("/api/generations") {
			header("Idempotency-Key", key)
			contentType = MediaType.APPLICATION_JSON
			content = body
		}.andExpect {
			status { isAccepted() }
			jsonPath("$.id") { value(runId.toString()) }
		}
		mockMvc.get("/api/generations/$runId").andExpect {
			status { isOk() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.evidence[0].snapshotExcerpt") { value("ORIGINAL PRIVATE EVIDENCE") }
		}
	}

	@Test
	fun `run detail omits a conflicting claim and returns a publishable result without intervention`() {
		val runId = UUID.randomUUID()
		val blockId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into writing_blocks (id, workspace_id, source_origin, source_kind, title, body, url, platform,
			 content_hash, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'github', 'pull_request', 'PR', 'conflicting evidence', 'https://github.test/pr/1', 'github',
			 'conflict-hash', now(), 'ACTIVE', ?, now(), now())
			""".trimIndent(), blockId, devContext.devWorkspaceId, devContext.devUserId,
		)
		val evidence = EvidenceSnapshot(
			UUID.randomUUID(), runId, blockId, 0, SourceProvider.GITHUB, "pull_request", "PR conflict", "PR conflict",
			"conflicting evidence", "CONFLICT SNAPSHOT", "https://github.test/pr/1", null, null, "conflict-hash", Instant.now(),
		)
		val state = workflow.start(runId, listOf(evidence), null)
		persistence.createRun(GenerationRunReservation(
			devContext.devWorkspaceId, devContext.devUserId, null, "conflict-${UUID.randomUUID()}", "conflict-fingerprint",
			state, "OPENAI", "scripted", "{\"maxModelCalls\":12,\"maxTotalTokens\":1000,\"maxRunDurationMillis\":60000}",
		))
		GenerationRunWorker(persistence, workflow, ApiConflictGateway(evidence.id), workerId = "api-conflict").drain()

		mockMvc.get("/api/generations/$runId").andExpect {
			status { isOk() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.status") { value("READY") }
			jsonPath("$.sentences.length()") { value(1) }
			jsonPath("$.sentences[0].body") { value("Grounded claim.") }
			jsonPath("$.pendingIntervention") { doesNotExist() }
			jsonPath("$.evidence[0].snapshotExcerpt") { value("CONFLICT SNAPSHOT") }
			jsonPath("$.artifacts[0].kind") { value("WRITER_OUTPUT") }
			jsonPath("$.artifacts[1].kind") { value("REVIEWER_OUTPUT") }
			jsonPath("$.artifacts[2].kind") { value("CONFLICT") }
			jsonPath("$.contentPack.status") { value("READY") }
			jsonPath("$.contentPack.variant.sentences.length()") { value(1) }
		}
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from generation_interventions where generation_run_id = ?",
			Int::class.java,
			runId,
		))
		assertEquals("READY", jdbcTemplate.queryForObject(
			"select status from generation_runs where id = ?",
			String::class.java,
			runId,
		))

		mockMvc.get("/api/generations/${UUID.randomUUID()}").andExpect {
			status { isNotFound() }
			header { string("Cache-Control", "no-store") }
			jsonPath("$.error") { value("GENERATION_NOT_FOUND") }
		}
	}

	@Test
	fun `unknown inactive and cross-scope blocks are rejected before invocation`() {
		val fixture = sourceFixture()
		val other = sourceFixture()
		fun rejected(key: String, blockId: UUID) {
			mockMvc.post("/api/generations") {
				header("Idempotency-Key", key)
				contentType = MediaType.APPLICATION_JSON
				content = """{"sourceScopeId":"${fixture.scopeId}","writingBlockIds":["$blockId"]}"""
			}.andExpect {
				status { isNotFound() }
				jsonPath("$.error") { value("SOURCE_NOT_FOUND") }
			}
		}
		rejected("access-cross-${UUID.randomUUID()}", other.blockId)
		rejected("access-unknown-${UUID.randomUUID()}", UUID.randomUUID())
		jdbcTemplate.update("update writing_blocks set status = 'ARCHIVED' where id = ?", fixture.blockId)
		rejected("access-archived-${UUID.randomUUID()}", fixture.blockId)
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from generation_runs where idempotency_key like 'access-%'", Int::class.java,
		))
	}

	private fun sourceFixture(): SourceFixture {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val blockId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at) values (?, ?, 'github', 'organization', ?, 'Acme', 'ACTIVE', now(), now())",
			namespaceId, devContext.devWorkspaceId, "org-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'github', 'CONTAINER', 'repository', ?, 'repo', 'ACTIVE', now(), now())
			""".trimIndent(), scopeId, devContext.devWorkspaceId, namespaceId, "repo-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into writing_blocks (id, workspace_id, source_namespace_id, external_object_key, source_origin, source_kind, title, body,
			 url, canonical_url, platform, content_hash, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, ?, ?, 'github', 'pull_request', 'PR', 'ORIGINAL PRIVATE EVIDENCE',
			 'https://github.test/acme/repo/pull/1', 'https://github.test/acme/repo/pull/1', 'github', 'hash', now(), 'ACTIVE', ?, now(), now())
			""".trimIndent(), blockId, devContext.devWorkspaceId, namespaceId, "pr-${UUID.randomUUID()}", devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into writing_block_scopes (id, workspace_id, source_namespace_id, writing_block_id, source_scope_id,
			 membership_kind, status, first_seen_at, last_seen_at)
			values (?, ?, ?, ?, ?, 'CONTAINED_IN', 'ACTIVE', now(), now())
			""".trimIndent(), UUID.randomUUID(), devContext.devWorkspaceId, namespaceId, blockId, scopeId,
		)
		return SourceFixture(scopeId, blockId)
	}
}

private data class SourceFixture(val scopeId: UUID, val blockId: UUID)

private class ApiConflictGateway(private val evidenceId: UUID) : GenerationModelGateway {
	override fun write(request: WriterModelRequest) = result(WriterOutput(listOf(
		WriterSentence("Conflicting claim."),
		WriterSentence("Grounded claim."),
	)))
	override fun review(request: ReviewerModelRequest) = result(ReviewerOutput(listOf(
		SentenceReview(request.sentences[0].id, ReviewVerdict.CONFLICT, listOf(evidenceId), "Sources disagree"),
		SentenceReview(request.sentences[1].id, ReviewVerdict.SUPPORTED, listOf(evidenceId)),
	)))
	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> = error("Unexpected rewrite")
	private fun <T : Any> result(value: T) = ModelCallResult(value, ModelCallMetadata(null, "scripted", "stop", 1, 1, 2, Duration.ofMillis(1), emptyMap()))
}
