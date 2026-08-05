package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
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
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("local")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.github.enabled=true",
	"plot.github.app-id=1",
	"plot.github.app-slug=plot",
	"plot.github.private-key=test-key",
	"plot.github.state-secret=test-state-secret",
	"plot.github.release-automation-enabled=false",
	"server.address=127.0.0.1",
])
class GitHubReleaseActivityApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	@BeforeEach
	fun cleanReleaseActivity() {
		jdbcTemplate.update("delete from github_release_generation_attempts")
		jdbcTemplate.update("delete from github_release_draft_evidence")
		jdbcTemplate.update("update content_packs set release_request_id = null where release_request_id is not null")
		jdbcTemplate.update("delete from github_release_draft_requests")
		jdbcTemplate.update("delete from github_webhook_deliveries")
	}

	@Test
	fun newRepositoryHasNoReleaseActivity() {
		val sourceScopeId = insertScope(devContext.devWorkspaceId)

		mockMvc.get("/api/github/repositories/$sourceScopeId/release-activity")
			.andExpect { status { isNoContent() } }
	}

	@Test
	fun latestActivityIsWorkspaceScopedAndContainsOnlySafeFields() {
		val sourceScopeId = insertScope(devContext.devWorkspaceId)
		val requestId = insertRequest(
			workspaceId = devContext.devWorkspaceId,
			sourceScopeId = sourceScopeId,
			status = "READY",
			baseSha = "a".repeat(40),
			headSha = "b".repeat(40),
		)
		val (generationRunId, artifactId) = attachReadyPack(requestId, sourceScopeId)
		val foreignWorkspaceId = insertWorkspace("foreign-release-activity")
		val foreignScopeId = insertScope(foreignWorkspaceId)
		insertRequest(foreignWorkspaceId, foreignScopeId, "FAILED", errorCode = "PRIVATE_PROVIDER_DETAIL")

		mockMvc.get("/api/github/repositories/$sourceScopeId/release-activity")
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(requestId.toString()) }
				jsonPath("$.sourceScopeId") { value(sourceScopeId.toString()) }
				jsonPath("$.tagName") { value("v1.2.0") }
				jsonPath("$.status") { value("READY") }
				jsonPath("$.baseSha") { value("a".repeat(40)) }
				jsonPath("$.headSha") { value("b".repeat(40)) }
				jsonPath("$.generationRunId") { value(generationRunId.toString()) }
				jsonPath("$.artifactId") { value(artifactId.toString()) }
				jsonPath("$.errorCode") { doesNotExist() }
				jsonPath("$.createdAt") { value("2026-07-30T00:00:00Z") }
				jsonPath("$.updatedAt") { value("2026-07-30T00:01:00Z") }
				jsonPath("$.workspaceId") { doesNotExist() }
				jsonPath("$.transitionVersion") { doesNotExist() }
				jsonPath("$.attemptCount") { doesNotExist() }
			}

		mockMvc.get("/api/github/repositories/$foreignScopeId/release-activity")
			.andExpect { status { isNotFound() } }
	}

	@Test
	fun retryAcceptsOnlyFailedActivityWithinCurrentWorkspaceAndSourceScope() {
		val sourceScopeId = insertScope(devContext.devWorkspaceId)
		val failedId = insertRequest(
			workspaceId = devContext.devWorkspaceId,
			sourceScopeId = sourceScopeId,
			status = "FAILED",
			errorCode = "GITHUB_PROVIDER_UNAVAILABLE",
		)
		val queuedId = insertRequest(
			workspaceId = devContext.devWorkspaceId,
			sourceScopeId = sourceScopeId,
			status = "QUEUED",
			tagName = "v1.3.0",
		)

		mockMvc.post("/api/github/repositories/$sourceScopeId/release-activity/$failedId/retry") {
			contentType = MediaType.APPLICATION_JSON
		}.andExpect {
			status { isOk() }
			jsonPath("$.id") { value(failedId.toString()) }
			jsonPath("$.sourceScopeId") { value(sourceScopeId.toString()) }
			jsonPath("$.status") { value("QUEUED") }
			jsonPath("$.errorCode") { doesNotExist() }
		}

		mockMvc.post("/api/github/repositories/$sourceScopeId/release-activity/$failedId/retry")
			.andExpect {
				status { isConflict() }
				jsonPath("$.error") { value("RELEASE_NOT_RETRYABLE") }
			}

		mockMvc.post("/api/github/repositories/$sourceScopeId/release-activity/$queuedId/retry")
			.andExpect {
				status { isConflict() }
				jsonPath("$.error") { value("RELEASE_NOT_RETRYABLE") }
			}

		val otherScopeId = insertScope(devContext.devWorkspaceId)
		mockMvc.post("/api/github/repositories/$otherScopeId/release-activity/$queuedId/retry")
			.andExpect { status { isNotFound() } }

		val foreignWorkspaceId = insertWorkspace("foreign-release-retry")
		val foreignScopeId = insertScope(foreignWorkspaceId)
		val foreignRequestId = insertRequest(foreignWorkspaceId, foreignScopeId, "FAILED")
		mockMvc.post("/api/github/repositories/$foreignScopeId/release-activity/$foreignRequestId/retry")
			.andExpect { status { isNotFound() } }
	}

	private fun insertWorkspace(slug: String): UUID {
		val id = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into workspaces (id, name, slug, status, created_at, updated_at)
			values (?, 'Foreign', ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			id,
			"$slug-${UUID.randomUUID()}",
		)
		return id
	}

	private fun insertScope(workspaceId: UUID): UUID {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into source_namespaces (
			 id, workspace_id, provider, namespace_kind, external_namespace_key,
			 display_name, status, created_at, updated_at
			) values (?, ?, 'GITHUB', 'REPOSITORY_OWNER', ?, 'acme', 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			workspaceId,
			"namespace-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes (
			 id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, external_key, display_name, status, created_at, updated_at
			) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/plot', 'acme/plot',
			 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			workspaceId,
			namespaceId,
			"scope-${UUID.randomUUID()}",
		)
		return scopeId
	}

	private fun insertRequest(
		workspaceId: UUID,
		sourceScopeId: UUID,
		status: String,
		tagName: String = "v1.2.0",
		baseSha: String? = null,
		headSha: String? = null,
		errorCode: String? = null,
	): UUID {
		val deliveryId = UUID.randomUUID()
		val requestId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into github_webhook_deliveries (
			 id, external_delivery_id, event_type, event_action, payload_hash, disposition, received_at
			) values (?, ?, 'release', 'published', ?, 'QUEUED', ?)
			""".trimIndent(),
			deliveryId,
			"delivery-${UUID.randomUUID()}",
			"0".repeat(64),
			Timestamp.from(Instant.parse("2026-07-30T00:00:00Z")),
		)
		jdbcTemplate.update(
			"""
			insert into github_release_draft_requests (
			 id, workspace_id, source_scope_id, initial_delivery_id, tag_name, base_sha, head_sha,
			 status, error_code, transition_version, created_at, updated_at, finished_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
			""".trimIndent(),
			requestId,
			workspaceId,
			sourceScopeId,
			deliveryId,
			tagName,
			baseSha,
			headSha,
			status,
			errorCode,
			Timestamp.from(Instant.parse("2026-07-30T00:00:00Z")),
			Timestamp.from(Instant.parse("2026-07-30T00:01:00Z")),
			if (status in setOf("READY", "NO_ACTIVITY", "NEEDS_RANGE", "FAILED")) {
				Timestamp.from(Instant.parse("2026-07-30T00:01:00Z"))
			} else {
				null
			},
		)
		return requestId
	}

	private fun attachReadyPack(requestId: UUID, sourceScopeId: UUID): Pair<UUID, UUID> {
		val generationRunId = UUID.randomUUID()
		val artifactId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into generation_runs (
			 id, workspace_id, source_scope_id, created_by_user_id, idempotency_key, request_fingerprint,
			 status, workflow_version, prompt_version, output_schema_version, budget_version, provider,
			 model_name, budget_snapshot, finished_at, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, 'READY', 'test-workflow', 'test-prompt', 'test-schema',
			 'test-budget', 'OPENAI', 'test-model', '{}'::jsonb, ?, ?, ?)
			""".trimIndent(),
			generationRunId,
			devContext.devWorkspaceId,
			sourceScopeId,
			devContext.devUserId,
			"activity-${UUID.randomUUID()}",
			"fingerprint-${UUID.randomUUID()}",
			Timestamp.from(Instant.parse("2026-07-30T00:01:00Z")),
			Timestamp.from(Instant.parse("2026-07-30T00:00:00Z")),
			Timestamp.from(Instant.parse("2026-07-30T00:01:00Z")),
		)
		jdbcTemplate.update(
			"update github_release_draft_requests set generation_run_id = ? where id = ?",
			generationRunId,
			requestId,
		)
		jdbcTemplate.update(
			"""
			insert into content_packs (
			 id, workspace_id, generation_run_id, title, status, created_at, updated_at, release_request_id
			) values (?, ?, ?, 'Release v1.2.0', 'READY', ?, ?, ?)
			""".trimIndent(),
			artifactId,
			devContext.devWorkspaceId,
			generationRunId,
			Timestamp.from(Instant.parse("2026-07-30T00:01:00Z")),
			Timestamp.from(Instant.parse("2026-07-30T00:01:00Z")),
			requestId,
		)
		return generationRunId to artifactId
	}
}
