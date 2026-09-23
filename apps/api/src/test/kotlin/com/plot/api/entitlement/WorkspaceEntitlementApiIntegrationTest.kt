package com.plot.api.entitlement

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.artifact.workflow.ArtifactWorkflowAdmissionPersistence
import com.plot.api.artifact.workflow.ArtifactWorkflowRunReservation
import com.plot.api.artifact.workflow.ArtifactWorkflowRunStatus
import com.plot.api.artifact.workflow.ArtifactWorkflowState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class WorkspaceEntitlementApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var persistence: ArtifactWorkflowAdmissionPersistence

	@BeforeEach
	@AfterEach
	fun restoreDevEntitlement() {
		jdbcTemplate.update(
			"""
			update workspaces
			set plan = 'founding',
			    entitlement_status = 'active',
			    access_mode = 'full'
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun revokedWorkspaceCanReadAndExportButCannotMutate() {
		setDevEntitlement("founding", "revoked", "read_only")

		mockMvc.get("/api/workspaces/${devContext.devWorkspaceId}").andExpect {
			status { isOk() }
			jsonPath("$.plan") { value("founding") }
			jsonPath("$.entitlementStatus") { value("revoked") }
			jsonPath("$.accessMode") { value("read_only") }
			jsonPath("$.capabilities.generate") { value(false) }
			jsonPath("$.capabilities.edit") { value(false) }
			jsonPath("$.capabilities.publish") { value(false) }
			jsonPath("$.capabilities.export") { value(true) }
			jsonPath("$.capabilities.configure") { value(false) }
			jsonPath("$.capabilities.unpublish") { value(true) }
		}
		mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Blocked"}"""
		}.andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("WORKSPACE_READ_ONLY") }
		}
		mockMvc.post("/api/artifact-variants/${UUID.randomUUID()}/exports") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"expectedRevisionNumber\":1,\"includeSources\":false}"
		}.andExpect {
			status { isNotFound() }
			jsonPath("$.error") { value("NOT_FOUND") }
		}
	}

	@Test
	fun newWorkspaceRequiresSubscriptionBeforeAnyWrite() {
		setDevEntitlement("none", "subscription_required", "read_only")

		mockMvc.get("/api/workspaces/${devContext.devWorkspaceId}").andExpect {
			status { isOk() }
			jsonPath("$.plan") { value("none") }
			jsonPath("$.entitlementStatus") { value("subscription_required") }
			jsonPath("$.accessMode") { value("read_only") }
			jsonPath("$.capabilities.generate") { value(false) }
			jsonPath("$.capabilities.edit") { value(false) }
			jsonPath("$.capabilities.publish") { value(false) }
			jsonPath("$.capabilities.export") { value(true) }
			jsonPath("$.capabilities.unpublish") { value(true) }
		}
		val projectedOnly = jdbcTemplate.queryForMap(
			"select entitlement_status, access_mode from workspaces where id = ?",
			devContext.devWorkspaceId,
		)
		assertEquals("subscription_required", projectedOnly["entitlement_status"])
		assertEquals("read_only", projectedOnly["access_mode"])
		mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Blocked"}"""
		}.andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("WORKSPACE_READ_ONLY") }
		}

		val state = jdbcTemplate.queryForMap(
			"select entitlement_status, access_mode from workspaces where id = ?",
			devContext.devWorkspaceId,
		)
		assertEquals("subscription_required", state["entitlement_status"])
		assertEquals("read_only", state["access_mode"])
	}

	@Test
	fun contentPacksCannotGrantFreeWorkspaceAccess() {
		val runIds = mutableListOf<UUID>()
		val packIds = mutableListOf<UUID>()
		try {
			setDevEntitlement("none", "subscription_required", "read_only")
			repeat(3) {
				val runId = insertArtifactWorkflowRun("READY")
				runIds += runId
				val packId = UUID.randomUUID()
				packIds += packId
				jdbcTemplate.update(
					"""
					insert into content_packs (
					  id, workspace_id, generation_run_id, title, status, created_at, updated_at
					) values (?, ?, ?, 'Test pack', 'READY', now(), now())
					""".trimIndent(),
					packId,
					devContext.devWorkspaceId,
					runId,
				)
			}
			repeat(2) { runIds += insertArtifactWorkflowRun("FAILED") }

			mockMvc.get("/api/workspaces/${devContext.devWorkspaceId}").andExpect {
				status { isOk() }
				jsonPath("$.plan") { value("none") }
				jsonPath("$.entitlementStatus") { value("subscription_required") }
				jsonPath("$.accessMode") { value("read_only") }
				jsonPath("$.capabilities.generate") { value(false) }
				jsonPath("$.capabilities.edit") { value(false) }
				jsonPath("$.capabilities.publish") { value(false) }
				jsonPath("$.capabilities.export") { value(true) }
				jsonPath("$.capabilities.configure") { value(false) }
				jsonPath("$.capabilities.unpublish") { value(true) }
			}
			mockMvc.patch("/api/workspaces/${devContext.devWorkspaceId}") {
				contentType = MediaType.APPLICATION_JSON
				content = """{"name":"Unpaid workspace"}"""
			}.andExpect {
				status { isForbidden() }
				jsonPath("$.error") { value("WORKSPACE_READ_ONLY") }
			}

			val persisted = jdbcTemplate.queryForMap(
				"select entitlement_status, access_mode from workspaces where id = ?",
				devContext.devWorkspaceId,
			)
			assertEquals("subscription_required", persisted["entitlement_status"])
			assertEquals("read_only", persisted["access_mode"])
		} finally {
			packIds.forEach { jdbcTemplate.update("delete from content_packs where id = ?", it) }
			runIds.forEach { jdbcTemplate.update("delete from generation_runs where id = ?", it) }
		}
	}

	@Test
	@Transactional
	fun subscribedWorkspaceCanReserveMoreThanThreeArtifactWorkflows() {
		val workspaceId = UUID.randomUUID()
		insertWorkspace(workspaceId)
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = 'active', access_mode = 'full' where id = ?",
			workspaceId,
		)
		repeat(4) {
			val runId = UUID.randomUUID()
			persistence.createRun(reservation(workspaceId, runId))
		}

		assertEquals(
			4,
			jdbcTemplate.queryForObject(
				"select count(*) from generation_runs where workspace_id = ?",
				Int::class.java,
				workspaceId,
			),
		)
	}

	@Test
	@Transactional
	fun subscriptionRequiredWorkspaceCannotReserveArtifactWorkflow() {
		val workspaceId = UUID.randomUUID()
		insertWorkspace(workspaceId)

		val blocked = assertFailsWith<ApiException> {
			persistence.createRun(reservation(workspaceId, UUID.randomUUID()))
		}
		assertEquals("WORKSPACE_READ_ONLY", blocked.error)
	}

	private fun setDevEntitlement(plan: String, status: String, accessMode: String) {
		jdbcTemplate.update(
			"""
			update workspaces
			set plan = ?,
			    entitlement_status = ?,
			    access_mode = ?
			where id = ?
			""".trimIndent(),
			plan,
			status,
			accessMode,
			devContext.devWorkspaceId,
		)
	}

	private fun insertArtifactWorkflowRun(status: String): UUID = UUID.randomUUID().also { runId ->
		jdbcTemplate.update(
			"""
			insert into generation_runs (
			  id, workspace_id, created_by_user_id, idempotency_key, request_fingerprint,
			  status, workflow_version, prompt_version, output_schema_version, budget_version,
			  provider, model_name, budget_snapshot, finished_at, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, 'test-v1', 'test-v1', 'test-v1', 'test-v1',
			  'TEST', 'test', '{}'::jsonb, now(), now(), now())
			""".trimIndent(),
			runId,
			devContext.devWorkspaceId,
			devContext.devUserId,
			"pack-$runId",
			"fingerprint-$runId",
			status,
		)
	}

	private fun insertWorkspace(workspaceId: UUID) {
		jdbcTemplate.update(
			"""
			insert into workspaces (
			  id, name, slug, created_by_user_id, status, created_at, updated_at
			) values (?, 'Quota test', ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			workspaceId,
			"quota-$workspaceId",
			devContext.devUserId,
		)
	}

	private fun reservation(workspaceId: UUID, runId: UUID) = ArtifactWorkflowRunReservation(
		workspaceId = workspaceId,
		createdByUserId = devContext.devUserId,
		sourceScopeId = null,
		idempotencyKey = "quota-$runId",
		requestFingerprint = "fingerprint-$runId",
		state = ArtifactWorkflowState(runId, emptyList(), null, ArtifactWorkflowRunStatus.QUEUED),
		provider = "TEST",
		modelName = "test",
		budgetJson = "{}",
	)
}
