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
import org.springframework.test.web.servlet.patch
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
	@org.junit.jupiter.api.AfterEach
	fun cleanPublishedEntries() {
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
			"update workspaces set public_citations_enabled = true where id = ?",
			devContext.devWorkspaceId,
		)
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
			jsonPath("$.sentences.length()") { value(2) }
			jsonPath("$.sentences[0].orderIndex") { value(0) }
			jsonPath("$.sentences[0].body") { value("Supported sentence.") }
			jsonPath("$.sentences[0].citations.length()") { value(1) }
			jsonPath("$.sentences[0].citations[0].provider") { value("GITHUB") }
			jsonPath("$.sentences[0].citations[0].sourceLabel") { value("PR 1") }
			jsonPath("$.sentences[0].citations[0].originalUrl") { value("https://github.test/acme/repo/pull/1") }
			jsonPath("$.sentences[1].citations.length()") { value(0) }
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

	@Test
	fun `agent artifacts retain public citations from per-input source scopes`() {
		val fixture = readyAgentPack()
		val publish = mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
		}.andExpect { status { isOk() } }.andReturn().response.contentAsString
		val entrySlug = Regex(""""entrySlug"\s*:\s*"([^"]+)"""").find(publish)?.groupValues?.get(1)
			?: error("Missing entry slug")

		mockMvc.get("/api/public/changelog/dev-workspace/$entrySlug").andExpect {
			status { isOk() }
			jsonPath("$.sentences[0].citations.length()") { value(1) }
			jsonPath("$.sentences[0].citations[0].provider") { value("GITHUB") }
			jsonPath("$.sentences[0].citations[0].sourceLabel") { value("PR 1") }
			jsonPath("$.sentences[0].citations[0].originalUrl") { value("https://github.test/acme/repo/pull/1") }
		}
	}

	@Test
	fun `private and unknown source visibility are omitted from public citation snapshots`() {
		listOf("PRIVATE", null).forEach { visibility ->
			val fixture = readyPack(visibility)
			val publish = mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
				contentType = MediaType.APPLICATION_JSON
				content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
			}.andExpect { status { isOk() } }.andReturn().response.contentAsString
			val entrySlug = Regex(""""entrySlug"\s*:\s*"([^"]+)"""").find(publish)?.groupValues?.get(1)
				?: error("Missing entry slug")

			mockMvc.get("/api/public/changelog/dev-workspace/$entrySlug").andExpect {
				status { isOk() }
				jsonPath("$.sentences.length()") { value(2) }
				jsonPath("$.sentences[0].body") { value("Supported sentence.") }
				jsonPath("$.sentences[0].citations.length()") { value(0) }
			}
		}
	}

	@Test
	fun `workspace citation visibility toggle hides citations without republishing`() {
		val fixture = readyPack("PUBLIC")
		val publish = mockMvc.post("/api/artifact-variants/${fixture.variantId}/publish") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"expectedRevisionNumber":1,"acknowledgeUnresolved":false}"""
		}.andExpect { status { isOk() } }.andReturn().response.contentAsString
		val entrySlug = Regex(""""entrySlug"\s*:\s*"([^"]+)"""").find(publish)?.groupValues?.get(1)
			?: error("Missing entry slug")

		mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"publicCitationsEnabled":false}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.publicCitationsEnabled") { value(false) }
		}

		mockMvc.get("/api/public/changelog/dev-workspace/$entrySlug").andExpect {
			status { isOk() }
			jsonPath("$.sentences[0].body") { value("Supported sentence.") }
			jsonPath("$.sentences[0].citations.length()") { value(0) }
		}
	}

	@Test
	fun `legacy published entries return an empty sentence snapshot`() {
		val fixture = readyPack()
		val revision = jdbcTemplate.queryForMap(
			"""
			select id, revision_no
			from content_variant_revisions
			where workspace_id = ? and content_variant_id = ?
			order by revision_no
			limit 1
			""".trimIndent(),
			devContext.devWorkspaceId,
			fixture.variantId,
		)
		val entryId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into published_changelog_entries (
			  id, workspace_id, content_variant_id, artifact_revision_id, artifact_revision_number,
			  entry_slug, title, body_markdown, published_by_user_id, published_at
			) values (?, ?, ?, ?, ?, 'legacy-entry', 'Legacy entry', 'Legacy body.',
			  ?, now())
			""".trimIndent(),
			entryId,
			devContext.devWorkspaceId,
			fixture.variantId,
			revision["id"] as UUID,
			revision["revision_no"] as Int,
			devContext.devUserId,
		)

		mockMvc.get("/api/public/changelog/dev-workspace/legacy-entry").andExpect {
			status { isOk() }
			jsonPath("$.bodyMarkdown") { value("Legacy body.") }
			jsonPath("$.sentences.length()") { value(0) }
		}
	}

	private fun readyPack(visibility: String? = "PUBLIC"): PublicFixture {
		val runId = UUID.randomUUID()
		val blockId = UUID.randomUUID()
		val sourceScopeId = insertSourceScope(visibility)
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
			devContext.devWorkspaceId, devContext.devUserId, sourceScopeId, "pack-${UUID.randomUUID()}", "fingerprint-${UUID.randomUUID()}",
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

	private fun readyAgentPack(): PublicFixture {
		val runId = UUID.randomUUID()
		val blockId = UUID.randomUUID()
		val sourceScopeId = insertSourceScope("PUBLIC")
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
			sourceScopeId = sourceScopeId,
		)
		val state = workflow.start(runId, listOf(evidence), null)
		admissionPersistence.createRun(ArtifactWorkflowRunReservation(
			devContext.devWorkspaceId, devContext.devUserId, null, "pack-${UUID.randomUUID()}", "fingerprint-${UUID.randomUUID()}",
			state, "OPENAI", "scripted", "{\"maxModelCalls\":12,\"maxTotalTokens\":1000,\"maxRunDurationMillis\":60000}",
		))
		val gateway = PublicPackGateway(evidence.id)
		ArtifactWorkflowRunWorker(executionPersistence, queryPersistence, workflow, gateway, workerId = "public-changelog-agent-test").drain()
		val variantId = jdbcTemplate.queryForObject(
			"select cv.id from content_packs cp join content_variants cv on cv.content_pack_id = cp.id where cp.generation_run_id = ?",
			UUID::class.java,
			runId,
		)!!
		return PublicFixture(variantId)
	}

	private fun insertSourceScope(visibility: String?): UUID {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into connections (
			  id, workspace_id, provider, connection_kind, external_connection_key,
			  status, created_by_user_id, created_at, updated_at
			) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())
			""".trimIndent(),
			connectionId,
			devContext.devWorkspaceId,
			"public-changelog-${UUID.randomUUID()}",
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces (
			  id, workspace_id, provider, namespace_kind, external_namespace_key,
			  display_name, status, created_at, updated_at
			) values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'acme', 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			devContext.devWorkspaceId,
			"installation-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into connection_namespace_bindings (
			  id, workspace_id, provider, connection_id, source_namespace_id,
			  status, valid_from, created_at, updated_at
			) values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())
			""".trimIndent(),
			bindingId,
			devContext.devWorkspaceId,
			connectionId,
			namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes (
			  id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			  external_scope_key, external_key, display_name, url, metadata, status, created_at, updated_at
			) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/repo', 'acme/repo',
			  null, ?::jsonb, 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			"repository-${UUID.randomUUID()}",
			visibility?.let { """{"visibility":"$it"}""" },
		)
		return scopeId
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
