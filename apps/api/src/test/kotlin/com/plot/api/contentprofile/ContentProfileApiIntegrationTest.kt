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
			jsonPath("$.contentProfileRevisionId") { value(revisionId) }
		}.andReturn().response.contentAsString
		val runId = UUID.fromString(objectMapper.readTree(admitted).path("id").asText())

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
		val nextRevision = objectMapper.readTree(nextAdmit).path("contentProfileRevisionId").asText()
		assertNotEquals(revisionId, nextRevision)
	}

	@Test
	fun `foreign content profile revision is rejected on chat admit`() {
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
		}.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("CONTENT_PROFILE_REVISION_NOT_FOUND") }
		}
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
		val providers = jdbcTemplate.query(
			"select source_provider from generation_inputs where generation_run_id = ?",
			{ rs, _ -> rs.getString(1) },
			state.runId,
		)
		assertTrue(providers.none { it == "USER_CONFIRMED" })
		assertTrue(providers.all { it == "GITHUB" })
	}

	@Test
	fun `confirmed facts materialize as USER_CONFIRMED evidence and freeze style lookup`() {
		val profile = mockMvc.put("/api/content-profile") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"productSummary":"Plot","tone":"calm"}"""
		}.andExpect { status { isOk() } }.andReturn().response.contentAsString
		val revisionId = objectMapper.readTree(profile).path("revisionId").asText()

		val sourceScopeId = insertSourceScope()
		val blockId = insertWritingBlock(sourceScopeId)
		val admitted = mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "brief-facts-1")
			contentType = MediaType.APPLICATION_JSON
			content = """
				{"instruction":"Announce beta","writingBlockIds":["$blockId"],
				 "brief":{"availability":"Public Monday","pricing":"Free",
				  "confirmedFacts":[{"body":"Public beta starts Monday","kind":"AVAILABILITY"}]}}
			""".trimIndent()
		}.andExpect {
			status { isAccepted() }
			jsonPath("$.contentProfileRevisionId") { value(revisionId) }
			jsonPath("$.brief.confirmedFacts[0].body") { value("Public beta starts Monday") }
		}.andReturn().response.contentAsString
		val runId = UUID.fromString(objectMapper.readTree(admitted).path("id").asText())
		val agentRun = requireNotNull(agentRunQuery.findAgentRun(devContext.devWorkspaceId, runId))
		val inputs = agentRunQuery.listAgentRunInputs(agentRun.workspaceId, agentRun.id)
		val state = artifactWorkflowRunService.createForAgent(
			WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId),
			agentRun,
			inputs,
			"artifact-brief-${runId}",
		)
		val confirmed = jdbcTemplate.query(
			"""
			select source_provider, original_url, writing_block_id, source_label, snapshot_body
			from generation_inputs where generation_run_id = ? and source_provider = 'USER_CONFIRMED'
			""".trimIndent(),
			{ rs, _ ->
				mapOf(
					"provider" to rs.getString(1),
					"url" to rs.getString(2),
					"block" to rs.getObject(3),
					"label" to rs.getString(4),
					"body" to rs.getString(5),
				)
			},
			state.runId,
		)
		assertEquals(1, confirmed.size)
		assertNull(confirmed[0]["url"])
		assertNull(confirmed[0]["block"])
		assertEquals("Public beta starts Monday", confirmed[0]["body"])

		mockMvc.put("/api/content-profile") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"tone":"loud"}"""
		}.andExpect { status { isOk() } }

		val frozen = frozenContentContextLookup.forRun(state.runId)
		assertNotNull(frozen.profile)
		assertEquals(UUID.fromString(revisionId), frozen.profile!!.id)
		assertEquals("calm", frozen.profile!!.tone)
		assertEquals("Public Monday", frozen.brief?.availability)
	}

	@Test
	fun `launch admission freezes launch-announcement-v3 on the artifact workflow`() {
		mockMvc.put("/api/content-profile") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"productSummary":"Plot","primaryAudience":"founders","tone":"direct"}"""
		}.andExpect { status { isOk() } }

		val sourceScopeId = insertSourceScope()
		val blockId = insertWritingBlock(sourceScopeId)
		val admitted = mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "launch-e2e-1")
			contentType = MediaType.APPLICATION_JSON
			content = """
				{"instruction":"Announce the waitlist","writingBlockIds":["$blockId"],
				 "contentType":"LAUNCH_ANNOUNCEMENT",
				 "brief":{"purpose":"Open waitlist","audience":"early customers",
				  "confirmedFacts":[{"body":"Waitlist opens Monday","kind":"AVAILABILITY"}]}}
			""".trimIndent()
		}.andExpect {
			status { isAccepted() }
			jsonPath("$.contentType") { value("LAUNCH_ANNOUNCEMENT") }
		}.andReturn().response.contentAsString
		val runId = UUID.fromString(objectMapper.readTree(admitted).path("id").asText())
		val agentRun = requireNotNull(agentRunQuery.findAgentRun(devContext.devWorkspaceId, runId))
		assertEquals(com.plot.api.content.ContentType.LAUNCH_ANNOUNCEMENT, agentRun.contentType)
		val inputs = agentRunQuery.listAgentRunInputs(agentRun.workspaceId, agentRun.id)
		val state = artifactWorkflowRunService.createForAgent(
			WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId),
			agentRun,
			inputs,
			"artifact-launch-${runId}",
		)
		val promptVersion = jdbcTemplate.queryForObject(
			"select prompt_version from generation_runs where id = ?",
			String::class.java,
			state.runId,
		)
		assertEquals(com.plot.api.content.ContentTypeRegistry.LAUNCH_PROMPT_VERSION, promptVersion)
		assertEquals("launch-announcement-v3", promptVersion)
	}

	@Test
	fun `launch admission without sources is rejected like changelog`() {
		mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "launch-no-source")
			contentType = MediaType.APPLICATION_JSON
			content = """{"instruction":"Announce","contentType":"LAUNCH_ANNOUNCEMENT"}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("SOURCE_NOT_READY") }
		}
	}

	@Test
	fun `idempotency rejects brief or profile mismatch`() {
		val sourceScopeId = insertSourceScope()
		val blockId = insertWritingBlock(sourceScopeId)
		val base = """{"instruction":"Draft","writingBlockIds":["$blockId"],"brief":{"pricing":"Free"}}"""
		mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "brief-idem")
			contentType = MediaType.APPLICATION_JSON
			content = base
		}.andExpect { status { isAccepted() } }

		mockMvc.post("/api/agent-runs") {
			header("Idempotency-Key", "brief-idem")
			contentType = MediaType.APPLICATION_JSON
			content = """{"instruction":"Draft","writingBlockIds":["$blockId"],"brief":{"pricing":"Paid"}}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("IDEMPOTENCY_KEY_REUSED") }
		}
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
