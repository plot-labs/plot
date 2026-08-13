package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.util.UUID
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true"])
class GitHubReleaseSchemaIntegrationTest {
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	@Test
	fun deliveryIdIsGloballyUnique() {
		val externalDeliveryId = "delivery-${UUID.randomUUID()}"
		insertDelivery(externalDeliveryId)

		assertFailsWith<DuplicateKeyException> { insertDelivery(externalDeliveryId) }
	}

	@Test
	fun workspaceRepositoryAndTagIdentifyOneReleaseRequest() {
		val scopeId = insertScope()
		insertRequest(scopeId, insertDelivery(), "v1.0.0")

		assertFailsWith<DuplicateKeyException> {
			insertRequest(scopeId, insertDelivery(), "v1.0.0")
		}
	}

	@Test
	fun generationRunCanBelongToOnlyOneReleaseRequest() {
		val scopeId = insertScope()
		val artifactWorkflowRunId = insertArtifactWorkflowRun(scopeId)
		val firstRequestId = insertRequest(scopeId, insertDelivery(), "v1.0.0")
		val secondRequestId = insertRequest(scopeId, insertDelivery(), "v1.1.0")

		jdbcTemplate.update(
			"update github_release_draft_requests set generation_run_id = ? where id = ?",
			artifactWorkflowRunId,
			firstRequestId,
		)

		assertFailsWith<DuplicateKeyException> {
			jdbcTemplate.update(
				"update github_release_draft_requests set generation_run_id = ? where id = ?",
				artifactWorkflowRunId,
				secondRequestId,
			)
		}
	}

	@Test
	fun invalidReleaseStatusIsRejected() {
		assertFailsWith<DataIntegrityViolationException> {
			insertRequest(insertScope(), insertDelivery(), "v1.0.0", status = "UNKNOWN")
		}
	}

	private fun insertDelivery(externalDeliveryId: String = "delivery-${UUID.randomUUID()}"): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update(
			"""
			insert into github_webhook_deliveries
			(id, external_delivery_id, event_type, payload_hash, disposition, received_at)
			values (?, ?, 'create', ?, 'RECEIVED', now())
			""".trimIndent(),
			id,
			externalDeliveryId,
			"a".repeat(64),
		)
	}

	private fun insertScope(): UUID {
		val namespaceId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			devContext.devWorkspaceId,
			"installation-${UUID.randomUUID()}",
		)
		return UUID.randomUUID().also { scopeId ->
			jdbcTemplate.update(
				"""
				insert into source_scopes
				(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
				 external_scope_key, display_name, status, created_at, updated_at)
				values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/repo', 'ACTIVE', now(), now())
				""".trimIndent(),
				scopeId,
				devContext.devWorkspaceId,
				namespaceId,
				"repository-${UUID.randomUUID()}",
			)
		}
	}

	private fun insertRequest(
		sourceScopeId: UUID,
		deliveryId: UUID,
		tagName: String,
		status: String = "QUEUED",
	): UUID = UUID.randomUUID().also { id ->
		jdbcTemplate.update(
			"""
			insert into github_release_draft_requests
			(id, workspace_id, source_scope_id, initial_delivery_id, tag_name, status, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, now(), now())
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			sourceScopeId,
			deliveryId,
			tagName,
			status,
		)
	}

	private fun insertArtifactWorkflowRun(sourceScopeId: UUID): UUID = UUID.randomUUID().also { id ->
		val key = UUID.randomUUID().toString()
		jdbcTemplate.update(
			"""
			insert into generation_runs
			(id, workspace_id, source_scope_id, created_by_user_id, idempotency_key, request_fingerprint,
			 status, workflow_version, prompt_version, output_schema_version, budget_version, provider,
			 model_name, budget_snapshot, finished_at, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, 'FAILED', 'fixed-v1', 'changelog-v1', 'generation-v1', 'budget-v1',
			 'OPENAI', 'configured-model', '{"maxModelCalls":12}'::jsonb, now(), now(), now())
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			sourceScopeId,
			devContext.devUserId,
			key,
			"fingerprint-$key",
		)
	}
}
