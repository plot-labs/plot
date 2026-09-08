package com.plot.api.artifact

import com.plot.api.TestcontainersConfiguration
import com.plot.api.content.ContentType
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.routine-agent.workers-enabled=true",
	"plot.routine-agent.auto-dispatch-enabled=false",
	"server.address=127.0.0.1",
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ArtifactReplicationIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var objectMapper: ObjectMapper

	@BeforeEach
	fun setup() {
		devBootstrapService.bootstrap()
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = 'active', access_mode = 'full', updated_at = now() where id = ?",
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun `replication of changelog to launch announcement creates queued agent run and content source snapshot with isolated inputs`() {
		val fixture = insertActiveArtifactFixture(ContentType.CHANGELOG, "Changelog v1.0.0")

		val result = mockMvc.post("/api/artifacts/${fixture.packId}/replicate") {
			contentType = MediaType.APPLICATION_JSON
			header("Idempotency-Key", "replicate-test-${UUID.randomUUID()}")
			content = """
				{
					"contentType": "LAUNCH_ANNOUNCEMENT",
					"instruction": "Craft a customer-facing launch announcement highlighting feature A"
				}
			""".trimIndent()
		}.andExpect {
			status { isAccepted() }
			header { exists("Location") }
			jsonPath("$.status") { value("QUEUED") }
			jsonPath("$.contentType") { value("LAUNCH_ANNOUNCEMENT") }
		}.andReturn()

		val responseNode = objectMapper.readTree(result.response.contentAsString)
		val newAgentRunId = UUID.fromString(responseNode.get("id").asText())

		// Verify child agent run has source_snapshot_id
		val snapshotId = jdbcTemplate.queryForObject(
			"select source_snapshot_id from agent_runs where workspace_id = ? and id = ?",
			UUID::class.java,
			devContext.devWorkspaceId,
			newAgentRunId,
		)
		assertNotNull(snapshotId)

		// Verify parent agent run got backfilled with the same snapshotId
		val parentSnapshotId = jdbcTemplate.queryForObject(
			"select source_snapshot_id from agent_runs where workspace_id = ? and id = ?",
			UUID::class.java,
			devContext.devWorkspaceId,
			fixture.agentRunId,
		)
		assertEquals(snapshotId, parentSnapshotId)

		// Verify content_source_snapshots row
		val snapshotCount = jdbcTemplate.queryForObject(
			"select count(*) from content_source_snapshots where workspace_id = ? and id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			snapshotId,
		)
		assertEquals(1, snapshotCount)

		// [E16] Input isolation: new agent run has brand-new input IDs
		val childInputIds = jdbcTemplate.queryForList(
			"select id from agent_run_inputs where workspace_id = ? and agent_run_id = ?",
			UUID::class.java,
			devContext.devWorkspaceId,
			newAgentRunId,
		)
		assertEquals(2, childInputIds.size)
		assertTrue(childInputIds.none { it in fixture.inputIds }, "Child input IDs must never reuse parent input IDs")

		// Verify agent_run_sources was created for the child run
		val sourcesCount = jdbcTemplate.queryForObject(
			"select count(*) from agent_run_sources where workspace_id = ? and agent_run_id = ? and source_scope_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			newAgentRunId,
			fixture.sourceScopeId,
		)
		assertEquals(1, sourcesCount)
	}

	@Test
	fun `idempotency replay with identical key returns same agent run`() {
		val fixture = insertActiveArtifactFixture(ContentType.CHANGELOG, "Changelog Idempotency Test")
		val idempotencyKey = "replicate-idemp-${UUID.randomUUID()}"
		val payload = """
			{
				"contentType": "LAUNCH_ANNOUNCEMENT",
				"instruction": "Explain the new features clearly"
			}
		""".trimIndent()

		val firstResponse = mockMvc.post("/api/artifacts/${fixture.packId}/replicate") {
			contentType = MediaType.APPLICATION_JSON
			header("Idempotency-Key", idempotencyKey)
			content = payload
		}.andExpect {
			status { isAccepted() }
		}.andReturn().response.contentAsString
		val firstRunId = objectMapper.readTree(firstResponse).get("id").asText()

		val secondResponse = mockMvc.post("/api/artifacts/${fixture.packId}/replicate") {
			contentType = MediaType.APPLICATION_JSON
			header("Idempotency-Key", idempotencyKey)
			content = payload
		}.andExpect {
			status { isAccepted() }
		}.andReturn().response.contentAsString
		val secondRunId = objectMapper.readTree(secondResponse).get("id").asText()

		assertEquals(firstRunId, secondRunId)
	}

	@Test
	fun `idempotency replay with conflicting payload returns 409 conflict`() {
		val fixture = insertActiveArtifactFixture(ContentType.CHANGELOG, "Changelog Conflict Test")
		val idempotencyKey = "replicate-conflict-${UUID.randomUUID()}"

		mockMvc.post("/api/artifacts/${fixture.packId}/replicate") {
			contentType = MediaType.APPLICATION_JSON
			header("Idempotency-Key", idempotencyKey)
			content = """
				{
					"contentType": "LAUNCH_ANNOUNCEMENT",
					"instruction": "Original instruction"
				}
			""".trimIndent()
		}.andExpect {
			status { isAccepted() }
		}

		mockMvc.post("/api/artifacts/${fixture.packId}/replicate") {
			contentType = MediaType.APPLICATION_JSON
			header("Idempotency-Key", idempotencyKey)
			content = """
				{
					"contentType": "LAUNCH_ANNOUNCEMENT",
					"instruction": "Conflicting instruction"
				}
			""".trimIndent()
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("IDEMPOTENCY_KEY_REUSED") }
		}
	}

	@Test
	fun `replication fails with 409 SOURCE_NOT_READY when connection is inactive`() {
		val fixture = insertActiveArtifactFixture(ContentType.CHANGELOG, "Revoked Source Artifact")
		jdbcTemplate.update(
			"update connections set status = 'DISABLED' where workspace_id = ? and id = ?",
			devContext.devWorkspaceId,
			fixture.connectionId,
		)

		mockMvc.post("/api/artifacts/${fixture.packId}/replicate") {
			contentType = MediaType.APPLICATION_JSON
			header("Idempotency-Key", "replicate-revoked-${UUID.randomUUID()}")
			content = """
				{
					"contentType": "LAUNCH_ANNOUNCEMENT",
					"instruction": "Should fail because source is revoked"
				}
			""".trimIndent()
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("SOURCE_NOT_READY") }
		}
	}

	@Test
	fun `artifacts sharing identical source snapshot appear in relatedArtifacts on GET`() {
		val fixture = insertActiveArtifactFixture(ContentType.CHANGELOG, "Parent Changelog Pack")

		// Replicate to create child agent run
		val replicateResponse = mockMvc.post("/api/artifacts/${fixture.packId}/replicate") {
			contentType = MediaType.APPLICATION_JSON
			header("Idempotency-Key", "replicate-related-${UUID.randomUUID()}")
			content = """
				{
					"contentType": "LAUNCH_ANNOUNCEMENT",
					"instruction": "Create launch note"
				}
			""".trimIndent()
		}.andExpect {
			status { isAccepted() }
		}.andReturn().response.contentAsString
		val childAgentRunId = UUID.fromString(objectMapper.readTree(replicateResponse).get("id").asText())

		// Materialize completed artifact pack for the child run
		val childArtifactRunId = UUID.randomUUID()
		val childGenRunId = UUID.randomUUID()
		val childPackId = UUID.randomUUID()
		val childVariantId = UUID.randomUUID()
		val childRevId = UUID.randomUUID()
		val now = Instant.now()

		jdbcTemplate.update(
			"""
			insert into artifact_runs (
			  id, workspace_id, agent_run_id, created_by_user_id, idempotency_key,
			  request_fingerprint, status, transition_version, started_at, finished_at,
			  created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, 'READY', 0, ?, ?, ?, ?)
			""".trimIndent(),
			childArtifactRunId, devContext.devWorkspaceId, childAgentRunId, devContext.devUserId,
			"art-${UUID.randomUUID()}", "art-fp-${UUID.randomUUID()}", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)
		jdbcTemplate.update(
			"""
			insert into generation_runs (
			  id, workspace_id, agent_run_id, artifact_run_id, source_scope_id, created_by_user_id,
			  idempotency_key, request_fingerprint, status, workflow_version, prompt_version,
			  output_schema_version, budget_version, provider, model_name, budget_snapshot,
			  transition_version, started_at, finished_at, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, 'READY', 'v1', 'p1', 's1', 'b1', 'provider', 'model', '{}'::jsonb, 0, ?, ?, ?, ?)
			""".trimIndent(),
			childGenRunId, devContext.devWorkspaceId, childAgentRunId, childArtifactRunId, fixture.sourceScopeId, devContext.devUserId,
			"gen-${UUID.randomUUID()}", "gen-fp-${UUID.randomUUID()}", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)
		jdbcTemplate.update(
			"insert into content_packs (id, workspace_id, generation_run_id, title, status, created_at, updated_at) values (?, ?, ?, 'Child Launch Note', 'READY', ?, ?)",
			childPackId, devContext.devWorkspaceId, childGenRunId, Timestamp.from(now), Timestamp.from(now),
		)
		jdbcTemplate.update(
			"insert into content_variants (id, workspace_id, generation_run_id, content_pack_id, variant_index, status, created_at, updated_at) values (?, ?, ?, ?, 0, 'READY', ?, ?)",
			childVariantId, devContext.devWorkspaceId, childGenRunId, childPackId, Timestamp.from(now), Timestamp.from(now),
		)
		jdbcTemplate.update(
			"insert into content_variant_revisions (id, workspace_id, generation_run_id, content_variant_id, revision_no, lexical_content, is_current, created_at) values (?, ?, ?, ?, 1, '{\"root\":{\"children\":[],\"direction\":null,\"format\":\"\",\"indent\":0,\"type\":\"root\",\"version\":1}}'::jsonb, true, ?)",
			childRevId, devContext.devWorkspaceId, childGenRunId, childVariantId, Timestamp.from(now),
		)

		// Check parent artifact shows child in relatedArtifacts
		mockMvc.get("/api/artifacts/${fixture.packId}").andExpect {
			status { isOk() }
			jsonPath("$.relatedArtifacts.length()") { value(1) }
			jsonPath("$.relatedArtifacts[0].id") { value(childPackId.toString()) }
			jsonPath("$.relatedArtifacts[0].title") { value("Child Launch Note") }
			jsonPath("$.relatedArtifacts[0].contentType") { value("LAUNCH_ANNOUNCEMENT") }
		}

		// Check child artifact shows parent in relatedArtifacts
		mockMvc.get("/api/artifacts/$childPackId").andExpect {
			status { isOk() }
			jsonPath("$.relatedArtifacts.length()") { value(1) }
			jsonPath("$.relatedArtifacts[0].id") { value(fixture.packId.toString()) }
			jsonPath("$.relatedArtifacts[0].title") { value("Parent Changelog Pack") }
			jsonPath("$.relatedArtifacts[0].contentType") { value("CHANGELOG") }
		}
	}

	private data class PackFixture(
		val packId: UUID,
		val variantId: UUID,
		val agentRunId: UUID,
		val generationRunId: UUID,
		val sourceScopeId: UUID,
		val connectionId: UUID,
		val inputIds: List<UUID>,
	)

	private fun insertActiveArtifactFixture(
		contentType: ContentType = ContentType.CHANGELOG,
		title: String = "Test Changelog Artifact",
	): PackFixture {
		val now = Instant.now()
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()

		jdbcTemplate.update(
			"""
			insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, ?, ?)
			""".trimIndent(),
			connectionId, devContext.devWorkspaceId, "conn-${UUID.randomUUID()}", devContext.devUserId, Timestamp.from(now), Timestamp.from(now),
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'test-org', 'ACTIVE', ?, ?)
			""".trimIndent(),
			namespaceId, devContext.devWorkspaceId, "ns-${UUID.randomUUID()}", Timestamp.from(now), Timestamp.from(now),
		)
		jdbcTemplate.update(
			"""
			insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at)
			values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', ?, ?, ?)
			""".trimIndent(),
			bindingId, devContext.devWorkspaceId, connectionId, namespaceId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, external_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, 'test-org/test-repo', 'ACTIVE', ?, ?)
			""".trimIndent(),
			scopeId, devContext.devWorkspaceId, namespaceId, "repo-${UUID.randomUUID()}", "test-org/test-repo", Timestamp.from(now), Timestamp.from(now),
		)

		val blockId1 = UUID.randomUUID()
		val blockId2 = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into writing_blocks (id, workspace_id, source_origin, source_kind, title, body, url, content_hash, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'github', 'pull_request', 'PR 1', 'Added feature A', 'https://github.com/test-org/test-repo/pull/1', 'hash-1', ?, 'ACTIVE', ?, ?, ?)
			""".trimIndent(),
			blockId1, devContext.devWorkspaceId, Timestamp.from(now), devContext.devUserId, Timestamp.from(now), Timestamp.from(now),
		)
		jdbcTemplate.update(
			"""
			insert into writing_blocks (id, workspace_id, source_origin, source_kind, title, body, url, content_hash, ingested_at, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'github', 'pull_request', 'PR 2', 'Fixed bug B', 'https://github.com/test-org/test-repo/pull/2', 'hash-2', ?, 'ACTIVE', ?, ?, ?)
			""".trimIndent(),
			blockId2, devContext.devWorkspaceId, Timestamp.from(now), devContext.devUserId, Timestamp.from(now), Timestamp.from(now),
		)

		val agentRunId = UUID.randomUUID()
		val sessionId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into work_sessions (id, workspace_id, title, status, created_by_user_id, created_at, updated_at) values (?, ?, 'Chat session', 'ACTIVE', ?, ?, ?)",
			sessionId, devContext.devWorkspaceId, devContext.devUserId, Timestamp.from(now), Timestamp.from(now),
		)

		jdbcTemplate.update(
			"""
			insert into agent_runs (
			  id, workspace_id, routine_execution_id, routine_id, work_session_id,
			  created_by_user_id, origin, idempotency_key, request_fingerprint,
			  instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot,
			  content_type, status, current_step, attempt_count, max_attempts, transition_version,
			  started_at, finished_at, created_at, updated_at
			) values (?, ?, null, null, ?, ?, 'CHAT', ?, ?, 'Instruction', 'prompt-v1', 'tool-v1', '{}'::jsonb, ?, 'SUCCEEDED', 0, 0, 3, 0, ?, ?, ?, ?)
			""".trimIndent(),
			agentRunId, devContext.devWorkspaceId, sessionId, devContext.devUserId, "parent-agent-${UUID.randomUUID()}", "fp-${UUID.randomUUID()}",
			contentType.name, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)

		val sourceBindingId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into agent_run_sources (
			  id, workspace_id, agent_run_id, source_scope_id, source_role, order_index,
			  captured_status, captured_status_changed_at, captured_at
			) values (?, ?, ?, ?, 'TRIGGER', 0, 'ACTIVE', ?, ?)
			""".trimIndent(),
			sourceBindingId, devContext.devWorkspaceId, agentRunId, scopeId, Timestamp.from(now), Timestamp.from(now),
		)

		val inputId1 = UUID.randomUUID()
		val inputId2 = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into agent_run_inputs (
			  id, workspace_id, agent_run_id, source_scope_id, writing_block_id, source_provider, source_kind, source_label,
			  input_kind, order_index, snapshot_title, snapshot_body, snapshot_excerpt, original_url, content_hash, captured_at
			) values (?, ?, ?, ?, ?, 'GITHUB', 'pull_request', 'PR 1', 'SEED', 0, 'PR 1', 'Added feature A', 'feature A excerpt', 'https://github.com/test-org/test-repo/pull/1', 'hash-1', ?)
			""".trimIndent(),
			inputId1, devContext.devWorkspaceId, agentRunId, scopeId, blockId1, Timestamp.from(now),
		)
		jdbcTemplate.update(
			"""
			insert into agent_run_inputs (
			  id, workspace_id, agent_run_id, source_scope_id, writing_block_id, source_provider, source_kind, source_label,
			  input_kind, order_index, snapshot_title, snapshot_body, snapshot_excerpt, original_url, content_hash, captured_at
			) values (?, ?, ?, ?, ?, 'GITHUB', 'pull_request', 'PR 2', 'SEED', 1, 'PR 2', 'Fixed bug B', 'bug B excerpt', 'https://github.com/test-org/test-repo/pull/2', 'hash-2', ?)
			""".trimIndent(),
			inputId2, devContext.devWorkspaceId, agentRunId, scopeId, blockId2, Timestamp.from(now),
		)

		val artifactRunId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into artifact_runs (
			  id, workspace_id, agent_run_id, created_by_user_id, idempotency_key,
			  request_fingerprint, status, transition_version, started_at, finished_at,
			  created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, 'READY', 0, ?, ?, ?, ?)
			""".trimIndent(),
			artifactRunId, devContext.devWorkspaceId, agentRunId, devContext.devUserId, "art-${UUID.randomUUID()}", "art-fp-${UUID.randomUUID()}",
			Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)

		val genRunId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into generation_runs (
			  id, workspace_id, agent_run_id, artifact_run_id, source_scope_id, created_by_user_id,
			  idempotency_key, request_fingerprint, status, workflow_version, prompt_version,
			  output_schema_version, budget_version, provider, model_name, budget_snapshot,
			  transition_version, started_at, finished_at, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, 'READY', 'v1', 'p1', 's1', 'b1', 'provider', 'model', '{}'::jsonb, 0, ?, ?, ?, ?)
			""".trimIndent(),
			genRunId, devContext.devWorkspaceId, agentRunId, artifactRunId, scopeId, devContext.devUserId,
			"gen-${UUID.randomUUID()}", "gen-fp-${UUID.randomUUID()}",
			Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)

		val packId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into content_packs (id, workspace_id, generation_run_id, title, status, created_at, updated_at) values (?, ?, ?, ?, 'READY', ?, ?)",
			packId, devContext.devWorkspaceId, genRunId, title, Timestamp.from(now), Timestamp.from(now),
		)

		val variantId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into content_variants (id, workspace_id, generation_run_id, content_pack_id, variant_index, status, created_at, updated_at) values (?, ?, ?, ?, 0, 'READY', ?, ?)",
			variantId, devContext.devWorkspaceId, genRunId, packId, Timestamp.from(now), Timestamp.from(now),
		)

		val revisionId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into content_variant_revisions (id, workspace_id, generation_run_id, content_variant_id, revision_no, lexical_content, is_current, created_at) values (?, ?, ?, ?, 1, '{\"root\":{\"children\":[],\"direction\":null,\"format\":\"\",\"indent\":0,\"type\":\"root\",\"version\":1}}'::jsonb, true, ?)",
			revisionId, devContext.devWorkspaceId, genRunId, variantId, Timestamp.from(now),
		)

		return PackFixture(packId, variantId, agentRunId, genRunId, scopeId, connectionId, listOf(inputId1, inputId2))
	}
}
