package com.plot.api.contentprofile

import com.plot.api.TestcontainersConfiguration
import com.plot.api.artifact.workflow.ArtifactWorkflowRunService
import com.plot.api.chat.ChatRunService
import com.plot.api.chat.dto.CreateChatAgentRunRequest
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.content.FrozenContentContextLookup
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.agent.AgentRunQueryPersistence
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.routines.schedule-scan-delay=PT1H",
	"plot.routine-agent.workers-enabled=false",
	"server.address=127.0.0.1",
])
class ContentProfileApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var objectMapper: ObjectMapper
	@Autowired private lateinit var chatAdmission: ChatRunService
	@Autowired private lateinit var agentRunQuery: AgentRunQueryPersistence
	@Autowired private lateinit var artifactWorkflowRunService: ArtifactWorkflowRunService
	@Autowired private lateinit var frozenContentContextLookup: FrozenContentContextLookup

	@BeforeEach
	fun clearWorkspace() {
		devBootstrapService.bootstrap()
		jdbcTemplate.execute("alter table generation_inputs disable trigger generation_inputs_append_only")
		jdbcTemplate.execute("alter table generation_artifacts disable trigger generation_artifacts_append_only")
		jdbcTemplate.update("delete from generation_inputs where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from generation_artifacts where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.execute("alter table generation_artifacts enable trigger generation_artifacts_append_only")
		jdbcTemplate.execute("alter table generation_inputs enable trigger generation_inputs_append_only")
		jdbcTemplate.update(
			"update work_sessions set latest_generation_run_id = null where workspace_id = ?",
			devContext.devWorkspaceId,
		)
		jdbcTemplate.update("delete from generation_runs where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from artifact_runs where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from agent_run_inputs where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from agent_run_sources where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from agent_runs where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from content_source_snapshots where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from work_sessions where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from writing_block_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from writing_blocks where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_namespaces where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connections where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from workspace_content_profiles where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from workspace_content_profile_revisions where workspace_id = ?", devContext.devWorkspaceId)
	}

	@Test
	fun `content profile persists across gets and freezes on chat admit`() {
		mockMvc.get("/api/content-profile").andExpect {
			status { isOk() }
			jsonPath("$.productSummary") { value("") }
		}

		val created = mockMvc.put("/api/content-profile") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{"productSummary":"Plot ships changelogs","primaryAudience":"founders","customerTerms":"workspace",
				 "tone":"direct","defaultLocale":"en","bannedPhrases":["synergy"]}
			""".trimIndent()
		}.andExpect {
			status { isOk() }
			jsonPath("$.revisionId") { exists() }
			jsonPath("$.productSummary") { value("Plot ships changelogs") }
			jsonPath("$.bannedPhrases[0]") { value("synergy") }
		}.andReturn().response.contentAsString
		val revisionId = objectMapper.readTree(created).path("revisionId").asText()

		mockMvc.get("/api/content-profile").andExpect {
			status { isOk() }
			jsonPath("$.revisionId") { value(revisionId) }
			jsonPath("$.productSummary") { value("Plot ships changelogs") }
		}

		val sourceScopeId = insertSourceScope()
		val blockId = insertWritingBlock(sourceScopeId)
		val admitted = mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "profile-freeze-1")
			contentType = MediaType.APPLICATION_JSON
			content = """{"instruction":"Draft","writingBlockIds":["$blockId"]}"""
		}.andExpect {
			status { isAccepted() }
		}.andReturn().response.contentAsString
		val runId = UUID.fromString(objectMapper.readTree(admitted).path("id").asText())
		assertEquals(
			"ARTIFACT",
			jdbcTemplate.queryForObject("select content_type from agent_runs where id = ?", String::class.java, runId),
		)

		mockMvc.put("/api/content-profile") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"productSummary":"2x faster runtime","tone":"hype"}"""
		}.andExpect { status { isOk() } }

		val frozenRevision = jdbcTemplate.queryForObject(
			"select content_profile_revision_id from agent_runs where id = ?",
			UUID::class.java,
			runId,
		)
		assertEquals(UUID.fromString(revisionId), frozenRevision)

		val nextAdmit = mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "profile-freeze-2")
			contentType = MediaType.APPLICATION_JSON
			content = """{"instruction":"Draft again","writingBlockIds":["$blockId"]}"""
		}.andExpect { status { isAccepted() } }.andReturn().response.contentAsString
		val nextRunId = UUID.fromString(objectMapper.readTree(nextAdmit).path("id").asText())
		val nextRevision = jdbcTemplate.queryForObject(
			"select content_profile_revision_id from agent_runs where id = ?",
			UUID::class.java,
			nextRunId,
		).toString()
		assertNotEquals(revisionId, nextRevision)
	}

	@Test
	fun `chat admission ignores caller supplied profile revision and freezes workspace current profile`() {
		val foreignWorkspace = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into workspaces (id, name, slug, created_by_user_id, status, plan, entitlement_status, access_mode, created_at, updated_at) values (?, 'Foreign', ?, ?, 'ACTIVE', 'founding', 'active', 'full', now(), now())",
			foreignWorkspace,
			"foreign-${UUID.randomUUID()}",
			devContext.devUserId,
		)
		val foreignRevision = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into workspace_content_profile_revisions (
			  id, workspace_id, revision_no, product_summary, primary_audience, customer_terms,
			  tone, default_locale, banned_phrases, created_by_user_id, created_at
			) values (?, ?, 1, 'foreign', 'x', 'y', 'z', 'en', '[]'::jsonb, ?, now())
			""".trimIndent(),
			foreignRevision,
			foreignWorkspace,
			devContext.devUserId,
		)
		val sourceScopeId = insertSourceScope()
		val blockId = insertWritingBlock(sourceScopeId)
		mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "foreign-profile")
			contentType = MediaType.APPLICATION_JSON
			content = """
				{"instruction":"Draft","writingBlockIds":["$blockId"],"contentProfileRevisionId":"$foreignRevision"}
			""".trimIndent()
		}.andExpect { status { isAccepted() } }
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from agent_runs where workspace_id = ? and content_profile_revision_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			foreignRevision,
		))
	}

	@Test
	fun `profile text alone does not create USER_CONFIRMED evidence`() {
		mockMvc.put("/api/content-profile") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"productSummary":"2x faster than competitors"}"""
		}.andExpect { status { isOk() } }

		val sourceScopeId = insertSourceScope()
		val blockId = insertWritingBlock(sourceScopeId)
		val admitted = chatAdmission.admit(
			CreateChatAgentRunRequest("Draft", writingBlockIds = listOf(blockId)),
			"no-user-confirmed",
		)
		val record = requireNotNull(agentRunQuery.findAgentRun(devContext.devWorkspaceId, admitted.id))
		val inputs = agentRunQuery.listAgentRunInputs(record.workspaceId, record.id)
		val state = artifactWorkflowRunService.createForAgent(
			WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId),
			record,
			inputs,
			"artifact-${record.id}",
		)
		assertEquals(
			"artifact-v1",
			jdbcTemplate.queryForObject("select prompt_version from generation_runs where id = ?", String::class.java, state.runId),
		)
		val providers = jdbcTemplate.query(
			"select source_provider from generation_inputs where generation_run_id = ?",
			{ rs, _ -> rs.getString(1) },
			state.runId,
		)
		assertTrue(providers.none { it == "USER_CONFIRMED" })
		assertTrue(providers.all { it == "GITHUB" })
	}

	@Test
	fun `agent admission without sources is accepted for general chat`() {
		mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "launch-no-source")
			contentType = MediaType.APPLICATION_JSON
			content = """{"instruction":"What makes a launch announcement effective?"}"""
		}.andExpect {
			status { isAccepted() }
			jsonPath("$.chatId") { isNotEmpty() }
		}
	}

	@Test
	fun `legacy brief fields do not classify an agent request or change idempotency`() {
		val sourceScopeId = insertSourceScope()
		val blockId = insertWritingBlock(sourceScopeId)
		val base = """{"instruction":"Draft","writingBlockIds":["$blockId"],"brief":{"pricing":"Free"}}"""
		val first = mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "brief-idem")
			contentType = MediaType.APPLICATION_JSON
			content = base
		}.andExpect { status { isAccepted() } }.andReturn().response.contentAsString

		val replay = mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "brief-idem")
			contentType = MediaType.APPLICATION_JSON
			content = """{"instruction":"Draft","writingBlockIds":["$blockId"],"brief":{"pricing":"Paid"}}"""
		}.andExpect { status { isAccepted() } }.andReturn().response.contentAsString
		assertEquals(objectMapper.readTree(first).path("id"), objectMapper.readTree(replay).path("id"))
	}

	private fun insertSourceScope(): UUID {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val workspaceId = devContext.devWorkspaceId
		val label = "acme/plot"
		jdbcTemplate.update(
			"insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())",
			connectionId,
			workspaceId,
			(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).toString(),
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			workspaceId,
			"installation-${UUID.randomUUID()}",
			label.substringBefore('/'),
		)
		jdbcTemplate.update(
			"insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at) values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())",
			bindingId,
			workspaceId,
			connectionId,
			namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, external_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			workspaceId,
			namespaceId,
			"repository-${UUID.randomUUID()}",
			label,
			label,
		)
		return scopeId
	}

	private fun insertWritingBlock(sourceScopeId: UUID): UUID {
		val blockId = UUID.randomUUID()
		val namespaceId = jdbcTemplate.queryForObject(
			"select source_namespace_id from source_scopes where workspace_id = ? and id = ?",
			UUID::class.java,
			devContext.devWorkspaceId,
			sourceScopeId,
		)!!
		val now = java.sql.Timestamp.from(java.time.Instant.now())
		jdbcTemplate.update(
			"""
			insert into writing_blocks (
			 id, workspace_id, source_origin, source_kind, title, body, url, canonical_url,
			 platform, content_hash, source_created_at, source_updated_at, ingested_at,
			 status, created_by_user_id, created_at, updated_at, source_namespace_id, external_object_key
			) values (?, ?, 'GITHUB', 'PULL_REQUEST', 'A source item', 'A source body',
			 'https://github.com/acme/plot/pull/1', 'https://github.com/acme/plot/pull/1',
			 'GITHUB', 'chat-block-hash', ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
			""".trimIndent(),
			blockId,
			devContext.devWorkspaceId,
			now,
			now,
			now,
			devContext.devUserId,
			now,
			now,
			namespaceId,
			"chat-block-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"insert into writing_block_scopes (id, workspace_id, source_namespace_id, writing_block_id, source_scope_id, membership_kind, status, first_seen_at, last_seen_at) values (?, ?, ?, ?, ?, 'OBSERVED_VIA', 'ACTIVE', ?, ?)",
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			namespaceId,
			blockId,
			sourceScopeId,
			now,
			now,
		)
		return blockId
	}
}
