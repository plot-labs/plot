package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

private const val GITHUB_WEBHOOK_SECRET = "test-github-webhook-secret"
private const val MAX_WEBHOOK_PAYLOAD_BYTES = 1_048_576

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, GitHubWebhookApiIntegrationTest.Config::class)
@ActiveProfiles("production-like")
@TestPropertySource(properties = [
	"plot.github.webhook-secret=$GITHUB_WEBHOOK_SECRET",
	"plot.auth.enabled=true",
	"plot.auth.required=true",
])
class GitHubWebhookApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var requestSizeFilter: GitHubWebhookRequestSizeFilter
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var recordingDispatcher: RecordingReleaseDispatcher
	@Autowired private lateinit var failingPersistence: FailingReleasePersistence

	@BeforeEach
	fun clearDeliveries() {
		devBootstrapService.bootstrap()
		jdbcTemplate.update("delete from github_release_draft_requests where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from generation_runs where workspace_id = ? and source_scope_id is not null", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from github_repository_access_checks where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from github_webhook_deliveries")
		jdbcTemplate.update("delete from github_repository_monitoring where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_namespaces where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connections where workspace_id = ?", devContext.devWorkspaceId)
		recordingDispatcher.reset()
		failingPersistence.failQueuedMark.set(false)
	}

	@Test
	fun unboundRepositoryDeliveryIsIgnoredWithoutCreatingAReleaseRequest() {
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val body = """
			{"action":"published","installation":{"id":77},"repository":{"id":99},"release":{"tag_name":"v1.2.3"}}
		""".trimIndent()

		postWebhook(deliveryId, "release", body).andExpect { status { isAccepted() } }

		val row = jdbcTemplate.queryForMap(
			"""select event_type, event_action, installation_id, repository_id, tag_name, ref, payload_hash, disposition, processed_at
				from github_webhook_deliveries where external_delivery_id = ?""".trimIndent(),
			deliveryId,
		)
		assertEquals("release", row["event_type"])
		assertEquals("published", row["event_action"])
		assertEquals(77L, row["installation_id"])
		assertEquals(99L, row["repository_id"])
		assertEquals("v1.2.3", row["tag_name"])
		assertEquals(null, row["ref"])
		assertEquals(64, (row["payload_hash"] as String).length)
		assertEquals("IGNORED", row["disposition"])
		assertEquals(0, releaseRequestCount())
		assertEquals(0, modelInvocationCount())
	}

	@Test
	fun webhookDeliveryDoesNotRequireWritableWorkspaceEntitlement() {
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = 'revoked', access_mode = 'read_only' where id = ?",
			devContext.devWorkspaceId,
		)
		try {
			val deliveryId = "delivery-${UUID.randomUUID()}"
			val body = """
				{"action":"published","installation":{"id":77},"repository":{"id":99},"release":{"tag_name":"v1.2.3"}}
			""".trimIndent()

			postWebhook(deliveryId, "release", body).andExpect { status { isAccepted() } }

			assertEquals("IGNORED", latestDisposition())
		} finally {
			jdbcTemplate.update(
				"update workspaces set plan = 'founding', entitlement_status = 'active', access_mode = 'full' where id = ?",
				devContext.devWorkspaceId,
			)
		}
	}

	@Test
	fun defaultBranchPushIsObservedWithoutQueuingReleaseOrGeneration() {
		bindRepository(repositoryId = 99)

		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"push",
			"""{"installation":{"id":77},"repository":{"id":99},"ref":"refs/heads/main","before":"a","after":"b","created":false,"deleted":false,"forced":false}""",
		).andExpect { status { isAccepted() } }

		assertEquals("OBSERVED", latestDisposition())
		assertEquals(0, releaseRequestCount())
		assertEquals(0, modelInvocationCount())
	}

	@Test
	fun tagPushAndPublishedReleaseCanonicalizeToOneQueuedReleaseRequest() {
		val boundRepository = bindRepository(repositoryId = 99)
		val tagHead = "b".repeat(40)
		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"push",
			"""{"installation":{"id":77},"repository":{"id":99},"ref":"refs/tags/v1.2.0","before":"${"a".repeat(40)}","after":"$tagHead","created":true,"deleted":false,"forced":false}""",
		).andExpect { status { isAccepted() } }
		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"release",
			"""{"action":"published","installation":{"id":77},"repository":{"id":99},"release":{"tag_name":"v1.2.0"}}""",
		).andExpect { status { isAccepted() } }

		assertEquals(1, releaseRequestCount())
		val request = jdbcTemplate.queryForMap(
			"select source_scope_id, tag_name, observed_head_sha, status from github_release_draft_requests",
		)
		assertEquals(boundRepository.scopeId, request["source_scope_id"])
		assertEquals("v1.2.0", request["tag_name"])
		assertEquals(tagHead, request["observed_head_sha"])
		assertEquals("QUEUED", request["status"])
		assertEquals(0, modelInvocationCount())
		assertEquals(0, recordingDispatcher.dispatches.get())
	}

	@Test
	fun publishedReleaseWithoutImmutableShaIsEnrichedByALaterCanonicalTagPush() {
		bindRepository(repositoryId = 99)
		val tagHead = "c".repeat(40)
		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"release",
			"""{"action":"published","installation":{"id":77},"repository":{"id":99},"release":{"tag_name":"v1.3.0","target_commitish":"main"}}""",
		).andExpect { status { isAccepted() } }
		assertEquals(
			null,
			jdbcTemplate.queryForObject(
				"select observed_head_sha from github_release_draft_requests where tag_name = 'v1.3.0'",
				String::class.java,
			),
		)

		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"push",
			"""{"installation":{"id":77},"repository":{"id":99},"ref":"refs/tags/v1.3.0","before":"${"0".repeat(40)}","after":"$tagHead","created":true,"deleted":false,"forced":false}""",
		).andExpect { status { isAccepted() } }

		assertEquals(1, releaseRequestCount())
		assertEquals(
			tagHead,
			jdbcTemplate.queryForObject(
				"select observed_head_sha from github_release_draft_requests where tag_name = 'v1.3.0'",
				String::class.java,
			),
		)
	}

	@Test
	fun canonicalTagRequestRejectsADifferentObservedHeadWithoutReplacingTheOriginal() {
		bindRepository(repositoryId = 99)
		val originalHead = "d".repeat(40)
		val movedHead = "e".repeat(40)
		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"push",
			"""{"installation":{"id":77},"repository":{"id":99},"ref":"refs/tags/v1.4.0","before":"${"0".repeat(40)}","after":"$originalHead","created":true,"deleted":false,"forced":false}""",
		).andExpect { status { isAccepted() } }

		assertFailsWith<Exception> {
			postWebhook(
				"delivery-${UUID.randomUUID()}",
				"push",
				"""{"installation":{"id":77},"repository":{"id":99},"ref":"refs/tags/v1.4.0","before":"$originalHead","after":"$movedHead","created":false,"deleted":false,"forced":false}""",
			)
		}

		assertEquals(1, releaseRequestCount())
		assertEquals(
			originalHead,
			jdbcTemplate.queryForObject(
				"select observed_head_sha from github_release_draft_requests where tag_name = 'v1.4.0'",
				String::class.java,
			),
		)
	}

	@Test
	fun nonDefaultDeletedAndForcedPushesAreIgnoredWithoutCreatingReleaseRequests() {
		bindRepository(repositoryId = 99)
		listOf(
			"""{"installation":{"id":77},"repository":{"id":99},"ref":"refs/heads/feature","before":"a","after":"b","created":false,"deleted":false,"forced":false}""",
			"""{"installation":{"id":77},"repository":{"id":99},"ref":"refs/heads/main","before":"a","after":"b","created":false,"deleted":true,"forced":false}""",
			"""{"installation":{"id":77},"repository":{"id":99},"ref":"refs/heads/main","before":"a","after":"b","created":false,"deleted":false,"forced":true}""",
		).forEach { body ->
			postWebhook("delivery-${UUID.randomUUID()}", "push", body).andExpect { status { isAccepted() } }
		}

		assertEquals(3, jdbcTemplate.queryForObject(
			"select count(*) from github_webhook_deliveries where disposition = 'IGNORED'", Int::class.java,
		))
		assertEquals(0, releaseRequestCount())
		assertEquals(0, modelInvocationCount())
	}

	@Test
	fun unsupportedReleaseActionAndInactiveBindingAreIgnored() {
		val boundRepository = bindRepository(repositoryId = 99)
		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"release",
			"""{"action":"edited","installation":{"id":77},"repository":{"id":99},"release":{"tag_name":"v1.2.0"}}""",
		).andExpect { status { isAccepted() } }
		jdbcTemplate.update("update connection_namespace_bindings set status = 'DISABLED' where id = ?", boundRepository.bindingId)
		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"release",
			"""{"action":"published","installation":{"id":77},"repository":{"id":99},"release":{"tag_name":"v1.2.0"}}""",
		).andExpect { status { isAccepted() } }

		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from github_webhook_deliveries where disposition = 'IGNORED'", Int::class.java,
		))
		assertEquals(0, releaseRequestCount())
		assertEquals(0, modelInvocationCount())
	}

	@Test
	fun disabledConnectionAndScopeAreIgnored() {
		val boundRepository = bindRepository(repositoryId = 99)
		jdbcTemplate.update(
			"update connections set status = 'DISABLED' where workspace_id = ? and external_connection_key = '77'",
			devContext.devWorkspaceId,
		)
		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"release",
			"""{"action":"published","installation":{"id":77},"repository":{"id":99},"release":{"tag_name":"v1.2.0"}}""",
		).andExpect { status { isAccepted() } }
		jdbcTemplate.update("update connections set status = 'ACTIVE' where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("update source_scopes set status = 'DISABLED' where id = ?", boundRepository.scopeId)
		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"release",
			"""{"action":"published","installation":{"id":77},"repository":{"id":99},"release":{"tag_name":"v1.2.1"}}""",
		).andExpect { status { isAccepted() } }

		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from github_webhook_deliveries where disposition = 'IGNORED'", Int::class.java,
		))
		assertEquals(0, releaseRequestCount())
		assertEquals(0, modelInvocationCount())
	}

	@Test
	fun installationSuspendFencesTheConnectionAndItsRepositoryScope() {
		val boundRepository = bindRepository(repositoryId = 99)
		insertMonitoring(boundRepository.scopeId)

		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"installation",
			"""{"action":"suspend","installation":{"id":77}}""",
		).andExpect { status { isAccepted() } }

		assertEquals("ERROR", jdbcTemplate.queryForObject(
			"select status from connections where external_connection_key = '77'", String::class.java,
		))
		assertEquals("INSTALLATION_SUSPENDED", jdbcTemplate.queryForObject(
			"select status_reason from connections where external_connection_key = '77'", String::class.java,
		))
		assertEquals("ERROR", scopeStatus(boundRepository.scopeId))
		assertEquals("DISABLED", monitoringStatus(boundRepository.scopeId))
		assertEquals("OBSERVED", latestDisposition())
	}

	@Test
	fun installationSuspendFencesNonTerminalReleaseAndGenerationWork() {
		val boundRepository = bindRepository(repositoryId = 99)
		insertMonitoring(boundRepository.scopeId)
		jdbcTemplate.update(
			"""
			update github_repository_monitoring
			set analysis_status = 'ANALYZING', claimed_by = 'monitor-worker', claimed_at = now()
			where source_scope_id = ?
			""".trimIndent(),
			boundRepository.scopeId,
		)
		val releaseRequestId = insertReleaseRequest(boundRepository.scopeId)
		val generationRunId = insertGenerationRun(boundRepository.scopeId)

		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"installation",
			"""{"action":"suspend","installation":{"id":77}}""",
		).andExpect { status { isAccepted() } }

		assertEquals(
			"FAILED:SOURCE_ACCESS_LOST",
			jdbcTemplate.queryForObject(
				"select analysis_status || ':' || last_error_code from github_repository_monitoring where source_scope_id = ?",
				String::class.java,
				boundRepository.scopeId,
			),
		)
		assertEquals(
			"FAILED:SOURCE_ACCESS_LOST",
			jdbcTemplate.queryForObject(
				"select status || ':' || error_code from github_release_draft_requests where id = ?",
				String::class.java,
				releaseRequestId,
			),
		)
		assertEquals(
			"FAILED:SOURCE_ACCESS_LOST",
			jdbcTemplate.queryForObject(
				"select status || ':' || error_code from generation_runs where id = ?",
				String::class.java,
				generationRunId,
			),
		)
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from github_release_draft_requests where id = ? and claimed_by is not null",
				Int::class.java,
				releaseRequestId,
			),
		)
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from generation_runs where id = ? and claimed_by is not null",
				Int::class.java,
				generationRunId,
			),
		)
	}

	@Test
	fun installationUninstallRevokesBindingsAndIsIdempotent() {
		val boundRepository = bindRepository(repositoryId = 99)
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val body = """{"action":"deleted","installation":{"id":77}}"""

		postWebhook(deliveryId, "installation", body).andExpect { status { isAccepted() } }
		postWebhook(deliveryId, "installation", body).andExpect { status { isAccepted() } }

		assertEquals("DISABLED", jdbcTemplate.queryForObject(
			"select status from connections where external_connection_key = '77'", String::class.java,
		))
		assertEquals("INSTALLATION_UNINSTALLED", jdbcTemplate.queryForObject(
			"select status_reason from connections where external_connection_key = '77'", String::class.java,
		))
		assertEquals("REVOKED", jdbcTemplate.queryForObject(
			"select status from connection_namespace_bindings where id = ?", String::class.java, boundRepository.bindingId,
		))
		assertEquals("DISABLED", scopeStatus(boundRepository.scopeId))
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from github_webhook_deliveries where event_type = 'installation'", Int::class.java,
		))
	}

	@Test
	fun repositoryGrantRemovalOnlyProjectsTheAffectedScope() {
		val affected = bindRepository(repositoryId = 99)
		val unaffected = bindRepository(repositoryId = 100)

		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"installation_repositories",
			"""{"action":"removed","installation":{"id":77},"repositories_removed":[{"id":99}]}""",
		).andExpect { status { isAccepted() } }

		assertEquals("ERROR", scopeStatus(affected.scopeId))
		assertEquals("GRANT_REMOVED", scopeReason(affected.scopeId))
		assertEquals("ACTIVE", scopeStatus(unaffected.scopeId))
		assertEquals("REVOKED", jdbcTemplate.queryForObject(
			"select status from connection_namespace_bindings where id = ?", String::class.java, affected.bindingId,
		))
		assertEquals("ACTIVE", jdbcTemplate.queryForObject(
			"select status from connection_namespace_bindings where id = ?", String::class.java, unaffected.bindingId,
		))
	}

	@Test
	fun transferQueuesRecheckAndUnsuspendDoesNotReactivateBeforeVerification() {
		val boundRepository = bindRepository(repositoryId = 99)

		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"repository",
			"""{"action":"transferred","installation":{"id":77},"repository":{"id":99}}""",
		).andExpect { status { isAccepted() } }

		assertEquals("ERROR", scopeStatus(boundRepository.scopeId))
		assertEquals("REPOSITORY_TRANSFERRED", scopeReason(boundRepository.scopeId))
		assertEquals("QUEUED", accessCheckStatus(boundRepository.scopeId))

		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"installation",
			"""{"action":"unsuspend","installation":{"id":77}}""",
		).andExpect { status { isAccepted() } }

		assertEquals("ERROR", scopeStatus(boundRepository.scopeId))
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from github_repository_access_checks where source_scope_id = ?", Int::class.java, boundRepository.scopeId,
		))
	}

	@Test
	fun unsupportedLifecycleActionIsIgnored() {
		bindRepository(repositoryId = 99)

		postWebhook(
			"delivery-${UUID.randomUUID()}",
			"installation",
			"""{"action":"created","installation":{"id":77}}""",
		).andExpect { status { isAccepted() } }

		assertEquals("IGNORED", latestDisposition())
		assertEquals("ACTIVE", jdbcTemplate.queryForObject(
			"select status from connections where external_connection_key = '77'", String::class.java,
		))
	}

	@Test
	fun ambiguousInstallationRepositoryBindingsAcrossWorkspacesAreIgnored() {
		val foreignWorkspaceId = insertOtherWorkspace()
		try {
			bindRepository(repositoryId = 99)
			bindRepository(repositoryId = 99, workspaceId = foreignWorkspaceId)

			postWebhook(
				"delivery-${UUID.randomUUID()}",
				"release",
				"""{"action":"published","installation":{"id":77},"repository":{"id":99},"release":{"tag_name":"v1.2.0"}}""",
			).andExpect { status { isAccepted() } }

			assertEquals("IGNORED", latestDisposition())
			assertEquals(0, releaseRequestCount())
		} finally {
			deleteWorkspaceBindings(foreignWorkspaceId)
			jdbcTemplate.update("delete from workspaces where id = ?", foreignWorkspaceId)
		}
	}

	@Test
	fun failedDeliveryFinalizationRollsBackAndTheSameDeliveryCanRetry() {
		bindRepository(repositoryId = 99)
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val body = """{"installation":{"id":77},"repository":{"id":99},"ref":"refs/tags/v1.2.0","before":"a","after":"b","created":true,"deleted":false,"forced":false}"""
		failingPersistence.failQueuedMark.set(true)

		assertFailsWith<Exception> { postWebhook(deliveryId, "push", body) }

		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from github_webhook_deliveries where external_delivery_id = ?", Int::class.java, deliveryId,
		))
		assertEquals(0, releaseRequestCount())

		postWebhook(deliveryId, "push", body).andExpect { status { isAccepted() } }
		assertEquals("QUEUED", latestDisposition())
		assertEquals(1, releaseRequestCount())
	}

	@Test
	fun acceptsADuplicateDeliveryWithoutCreatingASecondDurableRow() {
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val body = "{\"ref\":\"refs/tags/v2.0.0\",\"before\":\"a\",\"after\":\"b\",\"created\":true,\"deleted\":false,\"forced\":false}"

		postWebhook(deliveryId, "push", body).andExpect { status { isAccepted() } }
		postWebhook(deliveryId, "push", body).andExpect { status { isAccepted() } }

		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from github_webhook_deliveries where external_delivery_id = ?",
			Int::class.java,
			deliveryId,
		))
		assertEquals("v2.0.0", jdbcTemplate.queryForObject(
			"select tag_name from github_webhook_deliveries where external_delivery_id = ?",
			String::class.java,
			deliveryId,
		))
	}

	@Test
	fun ignoresReleaseTagsForNonPublishedReleaseActions() {
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val body = "{\"action\":\"edited\",\"release\":{\"tag_name\":\"v9.9.9\"}}"

		postWebhook(deliveryId, "release", body).andExpect { status { isAccepted() } }

		assertEquals(null, jdbcTemplate.queryForObject(
			"select tag_name from github_webhook_deliveries where external_delivery_id = ?",
			String::class.java,
			deliveryId,
		))
	}

	@Test
	fun rejectsADeclaredOversizedWebhookBody() {
		val response = MockHttpServletResponse()

		requestSizeFilter.doFilter(DeclaredOversizedWebhookRequest(), response, MockFilterChain())

		assertEquals(413, response.status)
	}

	@Test
	fun rejectsAnActualOversizedChunkedWebhookBody() {
		val request = ChunkedWebhookRequest(ByteArray(MAX_WEBHOOK_PAYLOAD_BYTES + 1) { 'a'.code.toByte() })
		val response = MockHttpServletResponse()

		requestSizeFilter.doFilter(request, response, MockFilterChain())

		assertEquals(413, response.status)
	}

	@Test
	fun rejectsAnInvalidSignatureBeforeInsertingADelivery() {
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val body = "{\"action\":\"published\",\"release\":{\"tag_name\":\"v1.0.0\"}}"

		mockMvc.post("/api/github/webhook") {
			contentType = MediaType.APPLICATION_JSON
			content = body
			header("X-GitHub-Delivery", deliveryId)
			header("X-GitHub-Event", "release")
			header("X-Hub-Signature-256", "sha256=${"0".repeat(64)}")
		}.andExpect {
			status { isUnauthorized() }
			jsonPath("$.error") { value("INVALID_GITHUB_WEBHOOK") }
		}
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from github_webhook_deliveries where external_delivery_id = ?",
			Int::class.java,
			deliveryId,
		))
	}

	@Test
	fun requiresDeliveryAndEventHeaders() {
		mockMvc.post("/api/github/webhook") {
			contentType = MediaType.APPLICATION_JSON
			content = "{}"
			header("X-Hub-Signature-256", sign("{}"))
		}.andExpect { status { isBadRequest() } }
	}

	@Test
	fun leavesOtherGitHubRoutesAuthenticated() {
		mockMvc.get("/api/github/installations/requests").andExpect { status { isUnauthorized() } }
	}

	private fun postWebhook(deliveryId: String, eventType: String, body: String) = mockMvc.post("/api/github/webhook") {
		contentType = MediaType.APPLICATION_JSON
		content = body
		header("X-GitHub-Delivery", deliveryId)
		header("X-GitHub-Event", eventType)
		header("X-Hub-Signature-256", sign(body))
	}

	private fun sign(body: String): String {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(GITHUB_WEBHOOK_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
		return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)))
	}

	private fun bindRepository(
		repositoryId: Long,
		workspaceId: UUID = devContext.devWorkspaceId,
		createdByUserId: UUID = devContext.devUserId,
	): BoundRepository {
		val connectionId = jdbcTemplate.query(
			"select id from connections where workspace_id = ? and provider = 'GITHUB' and external_connection_key = '77'",
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			workspaceId,
		).firstOrNull() ?: UUID.randomUUID().also { id ->
			jdbcTemplate.update(
				"""
				insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at)
				values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', '77', 'ACTIVE', ?, now(), now())
				""".trimIndent(), id, workspaceId, createdByUserId,
			)
		}
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'ACTIVE', now(), now())
			""".trimIndent(), namespaceId, workspaceId, "repository:$repositoryId",
		)
		jdbcTemplate.update(
			"""
			insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, capabilities, status, valid_from, created_at, updated_at)
			values (?, ?, 'GITHUB', ?, ?, '{"metadata":"read","webhook_monitoring":"active"}'::jsonb, 'ACTIVE', now(), now(), now())
			""".trimIndent(), bindingId, workspaceId, connectionId, namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, external_key, display_name, metadata, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/repo', 'acme/repo', '{"repositoryId":$repositoryId,"defaultBranch":"main"}'::jsonb, 'ACTIVE', now(), now())
			""".trimIndent(), scopeId, workspaceId, namespaceId, repositoryId.toString(),
		)
		return BoundRepository(scopeId, bindingId)
	}

	private fun insertMonitoring(scopeId: UUID) {
		jdbcTemplate.update(
			"""
			insert into github_repository_monitoring (
			  id, workspace_id, source_scope_id, monitoring_status, analysis_status,
			  sample_size, sample_truncated, attempt_count, transition_version, created_at, updated_at
			) values (?, ?, ?, 'ACTIVE', 'QUEUED', 0, false, 0, 0, now(), now())
			""".trimIndent(), UUID.randomUUID(), devContext.devWorkspaceId, scopeId,
		)
	}

	private fun insertReleaseRequest(scopeId: UUID): UUID {
		val deliveryId = UUID.randomUUID()
		val requestId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into github_webhook_deliveries
			(id, external_delivery_id, event_type, installation_id, repository_id, payload_hash, disposition, received_at)
			values (?, ?, 'release', 77, 99, ?, 'QUEUED', now())
			""".trimIndent(),
			deliveryId,
			"fixture-$deliveryId",
			"a".repeat(64),
		)
		jdbcTemplate.update(
			"""
			insert into github_release_draft_requests
			(id, workspace_id, source_scope_id, initial_delivery_id, tag_name, status,
			 claimed_by, claimed_at, heartbeat_at, created_at, updated_at)
			values (?, ?, ?, ?, ?, 'GENERATING', 'release-worker', now(), now(), now(), now())
			""".trimIndent(),
			requestId,
			devContext.devWorkspaceId,
			scopeId,
			deliveryId,
			"v-${UUID.randomUUID()}",
		)
		return requestId
	}

	private fun insertGenerationRun(scopeId: UUID): UUID {
		val runId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into generation_runs
			(id, workspace_id, source_scope_id, created_by_user_id, idempotency_key, request_fingerprint,
			 status, workflow_version, prompt_version, output_schema_version, budget_version,
			 provider, model_name, budget_snapshot, claimed_by, claimed_at, heartbeat_at,
			 started_at, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, 'WRITING', 'fixed-v1', 'changelog-v8', 'generation-v5',
			 'budget-v1', 'OPENAI', 'test-model', '{"maxModelCalls":1}'::jsonb,
			 'generation-worker', now(), now(), now(), now(), now())
			""".trimIndent(),
			runId,
			devContext.devWorkspaceId,
			scopeId,
			devContext.devUserId,
			"fixture-$runId",
			"fingerprint-$runId",
		)
		return runId
	}

	private fun latestDisposition(): String = jdbcTemplate.queryForObject(
		"select disposition from github_webhook_deliveries order by received_at desc, id desc limit 1", String::class.java,
	)!!

	private fun releaseRequestCount(): Int = jdbcTemplate.queryForObject(
		"select count(*) from github_release_draft_requests", Int::class.java,
	)!!

	private fun modelInvocationCount(): Int = jdbcTemplate.queryForObject(
		"select count(*) from model_invocations", Int::class.java,
	)!!

	private fun scopeStatus(scopeId: UUID): String = jdbcTemplate.queryForObject(
		"select status from source_scopes where id = ?", String::class.java, scopeId,
	)!!

	private fun scopeReason(scopeId: UUID): String = jdbcTemplate.queryForObject(
		"select status_reason from source_scopes where id = ?", String::class.java, scopeId,
	)!!

	private fun monitoringStatus(scopeId: UUID): String = jdbcTemplate.queryForObject(
		"select monitoring_status from github_repository_monitoring where source_scope_id = ?", String::class.java, scopeId,
	)!!

	private fun accessCheckStatus(scopeId: UUID): String = jdbcTemplate.queryForObject(
		"select status from github_repository_access_checks where source_scope_id = ?", String::class.java, scopeId,
	)!!

	private data class BoundRepository(val scopeId: UUID, val bindingId: UUID)

	private fun insertOtherWorkspace(): UUID = UUID.randomUUID().also { workspaceId ->
		jdbcTemplate.update(
			"""insert into workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at)
				values (?, 'Webhook ambiguity', ?, ?, 'ACTIVE', now(), now())""".trimIndent(),
			workspaceId,
			"webhook-ambiguity-$workspaceId",
			devContext.devUserId,
		)
	}

	private fun deleteWorkspaceBindings(workspaceId: UUID) {
		jdbcTemplate.update("delete from github_repository_access_checks where workspace_id = ?", workspaceId)
		jdbcTemplate.update("delete from source_scopes where workspace_id = ?", workspaceId)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ?", workspaceId)
		jdbcTemplate.update("delete from source_namespaces where workspace_id = ?", workspaceId)
		jdbcTemplate.update("delete from connections where workspace_id = ?", workspaceId)
	}

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun recordingReleaseDispatcher() = RecordingReleaseDispatcher()

		@Bean
		@Primary
		fun failingReleasePersistence(delegate: JdbcGitHubReleasePersistence) = FailingReleasePersistence(delegate)
	}

	private class ChunkedWebhookRequest(body: ByteArray) : MockHttpServletRequest("POST", "/api/github/webhook") {
		init {
			contentType = MediaType.APPLICATION_JSON_VALUE
			setContent(body)
			addHeader("Transfer-Encoding", "chunked")
		}

		override fun getContentLength(): Int = -1

		override fun getContentLengthLong(): Long = -1
	}

	private class DeclaredOversizedWebhookRequest : MockHttpServletRequest("POST", "/api/github/webhook") {
		init {
			setContent("{}".toByteArray(StandardCharsets.UTF_8))
		}

		override fun getContentLength(): Int = MAX_WEBHOOK_PAYLOAD_BYTES + 1

		override fun getContentLengthLong(): Long = (MAX_WEBHOOK_PAYLOAD_BYTES + 1).toLong()
	}
}

class RecordingReleaseDispatcher : GitHubReleaseDraftDispatcher {
	val dispatches = AtomicInteger()

	override fun dispatch() {
		dispatches.incrementAndGet()
	}

	fun reset() {
		dispatches.set(0)
	}
}

class FailingReleasePersistence(
	private val delegate: GitHubReleasePersistence,
) : GitHubReleasePersistence by delegate {
	val failQueuedMark = AtomicBoolean()

	override fun markDelivery(id: UUID, disposition: GitHubWebhookDisposition, errorCode: String?) {
		if (disposition == GitHubWebhookDisposition.QUEUED && failQueuedMark.compareAndSet(true, false)) {
			throw IllegalStateException("planned queued-delivery failure")
		}
		delegate.markDelivery(id, disposition, errorCode)
	}
}
