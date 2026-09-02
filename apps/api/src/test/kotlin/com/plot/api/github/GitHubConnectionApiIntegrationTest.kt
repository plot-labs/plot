package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, GitHubConnectionApiIntegrationTest.Config::class)
@ActiveProfiles("local")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.github.enabled=true",
	"plot.github.app-id=1",
	"plot.github.app-slug=plot",
	"plot.github.private-key=test-key",
	"plot.github.state-secret=test-state-secret",
	"server.address=127.0.0.1",
])
class GitHubConnectionApiIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var devContext: DevContext

	@Autowired
	private lateinit var fakeClient: FakeGitHubClient

	@Autowired
	private lateinit var monitoringWorker: GitHubRepositoryMonitoringWorker

	@BeforeEach
	fun cleanData() {
		jdbcTemplate.update("delete from routine_context_sources where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from routines where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from writing_block_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_imports where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_observations where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from writing_blocks where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from github_repository_monitoring where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from github_repository_access_checks where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_namespaces where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connections where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from github_installation_states where workspace_id = ?", devContext.devWorkspaceId)
		seedLinkedGitHubAccount()
		fakeClient.reset()
	}

	/** The dev user normally only exists after a real GitHub OAuth login; link it by hand. */
	private fun seedLinkedGitHubAccount() {
		jdbcTemplate.update(
			"insert into auth_user (id, name, email, email_verified, created_at, updated_at) " +
				"values ('auth-user-dev', 'Dev User', 'dev-github@example.com', true, now(), now()) " +
				"on conflict (id) do nothing",
		)
		jdbcTemplate.update(
			"insert into auth_account (id, account_id, provider_id, issuer, user_id, access_token, created_at, updated_at) " +
				"values ('acct-dev', '9001', 'github', 'local:oauth:github', 'auth-user-dev', 'gh-token', now(), now()) " +
				"on conflict do nothing",
		)
		jdbcTemplate.update("update users set auth_subject = 'auth-user-dev' where id = ?", devContext.devUserId)
	}

	@Test
	fun cleanInstallationCanActivateImportAndCreateRoutineWithoutDatabaseSetup() {
		val connectionId = completeInstallation()
		val scopeId = connect(connectionId, 1001)

		mockMvc.post("/api/github/repositories/$scopeId/imports") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"from":"2026-01-01T00:00:00Z","to":"2026-02-01T00:00:00Z"}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.blockCreatedCount") { value(1) }
		}

		mockMvc.post("/api/routines") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{"name":"Repository update","sourceScopeId":"$scopeId","contextSourceScopeIds":[],"instruction":"Draft the latest update","cadence":"WEEKLY"}
			""".trimIndent()
		}.andExpect {
			status { isCreated() }
			jsonPath("$.sourceScopeId") { value(scopeId.toString()) }
			jsonPath("$.sourceLabel") { value("acme/one") }
		}

		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from routines where workspace_id = ? and source_scope_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			scopeId,
		))
	}

	@Test
	fun syncExistingInstallationLinksAlreadyInstalledGitHubApp() {
		mockMvc.post("/api/github/installations/sync")
			.andExpect {
				status { isOk() }
				jsonPath("$.connectionId") { exists() }
				jsonPath("$.installationId") { value(77) }
				header { string("Cache-Control", "no-store") }
			}

		mockMvc.get("/api/github/connections")
			.andExpect {
				status { isOk() }
				jsonPath("$.length()") { value(1) }
				jsonPath("$[0].status") { value("ACTIVE") }
			}
	}

	@Test
	fun syncExistingInstallationReturnsNotFoundWhenGitHubHasNoPlotInstall() {
		fakeClient.userInstallations = emptyList()

		mockMvc.post("/api/github/installations/sync")
			.andExpect {
				status { isNotFound() }
				jsonPath("$.error") { value("GITHUB_INSTALLATION_NOT_FOUND") }
			}
	}

	@Test
	fun installationCallbackListsGrantedRepositoriesAndStateCannotBeReused() {
		val state = mockMvc.post("/api/github/installations/requests")
			.andExpect { status { isOk() }; header { string("Cache-Control", "no-store") } }
			.andReturn().response.contentAsString
		val stateValue = Regex("\"state\":\"([^\"]+)\"").find(state)!!.groupValues[1]

		mockMvc.post("/api/github/installations/callback") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"state\":\"$stateValue\",\"installationId\":77}"
		}.andExpect {
			status { isOk() }
			jsonPath("$.connectionId") { exists() }
			jsonPath("$.repositories.length()") { value(2) }
			header { string("Cache-Control", "no-store") }
		}

		mockMvc.post("/api/github/installations/callback") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"state\":\"$stateValue\",\"installationId\":77}"
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("INVALID_GITHUB_STATE") }
		}
		assertEquals(1, fakeClient.repositoryListCalls.get())
	}

	@Test
	fun installationCallbackRejectsInstallationsNotOwnedByTheCaller() {
		fakeClient.installationAccount = GitHubInstallationAccount(999999, "victim", "User")
		val state = mockMvc.post("/api/github/installations/requests")
			.andReturn().response.contentAsString
		val stateValue = Regex("\"state\":\"([^\"]+)\"").find(state)!!.groupValues[1]

		mockMvc.post("/api/github/installations/callback") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"state\":\"$stateValue\",\"installationId\":77}"
		}.andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("GITHUB_INSTALLATION_NOT_OWNED") }
		}
		assertEquals(0, fakeClient.repositoryListCalls.get())
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from connections where workspace_id = ?",
				Int::class.java,
				devContext.devWorkspaceId,
			),
		)
	}

	@Test
	fun installationCallbackRejectsCallersWithoutALinkedGitHubAccount() {
		jdbcTemplate.update("update users set auth_subject = null where id = ?", devContext.devUserId)
		val state = mockMvc.post("/api/github/installations/requests")
			.andReturn().response.contentAsString
		val stateValue = Regex("\"state\":\"([^\"]+)\"").find(state)!!.groupValues[1]

		mockMvc.post("/api/github/installations/callback") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"state\":\"$stateValue\",\"installationId\":77}"
		}.andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("GITHUB_INSTALLATION_NOT_OWNED") }
		}
	}

	@Test
	fun organizationInstallationsRequireAdminMembershipOfTheCaller() {
		fakeClient.installationAccount = GitHubInstallationAccount(555, "acme-org", "Organization")
		val state = mockMvc.post("/api/github/installations/requests")
			.andReturn().response.contentAsString
		val stateValue = Regex("\"state\":\"([^\"]+)\"").find(state)!!.groupValues[1]

		fakeClient.membershipRole = "member"
		mockMvc.post("/api/github/installations/callback") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"state\":\"$stateValue\",\"installationId\":77}"
		}.andExpect {
			status { isForbidden() }
			jsonPath("$.error") { value("GITHUB_INSTALLATION_NOT_OWNED") }
		}

		fakeClient.membershipRole = "admin"
		val adminState = mockMvc.post("/api/github/installations/requests")
			.andReturn().response.contentAsString
		val adminStateValue = Regex("\"state\":\"([^\"]+)\"").find(adminState)!!.groupValues[1]
		mockMvc.post("/api/github/installations/callback") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"state\":\"$adminStateValue\",\"installationId\":77}"
		}.andExpect { status { isOk() } }
	}

	@Test
	fun providerCallsDoNotHoldThePersistenceTransaction() {
		val connectionId = completeInstallation()
		connect(connectionId, 1001)
		mockMvc.get("/api/github/connections/$connectionId/repositories")
			.andExpect { status { isOk() } }

		assertFalse(fakeClient.providerCallObservedActiveTransaction.get())
	}

	@Test
	fun connectsMultipleRepositoriesAndImportCreatesSourceManagedBlock() {
		val connectionId = completeInstallation()
		val firstContainerId = connect(connectionId, 1001)
		connect(connectionId, 1002)

		mockMvc.post("/api/github/repositories/$firstContainerId/imports") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-02-01T00:00:00Z\"}"
		}.andExpect {
			status { isOk() }
			jsonPath("$.status") { value("COMPLETED") }
			jsonPath("$.eligibleCount") { value(1) }
			jsonPath("$.blockCreatedCount") { value(1) }
		}

		val blockId = jdbcTemplate.queryForObject(
			"select writing_block_id from writing_block_scopes where workspace_id = ? and source_scope_id = ?",
			UUID::class.java,
			devContext.devWorkspaceId,
			firstContainerId,
		)!!
		mockMvc.get("/api/blocks?sourceScopeId=$firstContainerId")
			.andExpect {
				status { isOk() }
				jsonPath("$.items[0].id") { value(blockId.toString()) }
				jsonPath("$.items[0].sourceManaged") { value(true) }
			}
		fakeClient.body = "Changed body"
		fakeClient.updatedAt = Instant.parse("2026-01-03T00:00:00Z")
		mockMvc.post("/api/github/repositories/$firstContainerId/imports") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-02-01T00:00:00Z\"}"
		}.andExpect {
			status { isOk() }
			jsonPath("$.blockCreatedCount") { value(0) }
			jsonPath("$.blockUpdatedCount") { value(1) }
		}
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from writing_block_scopes where workspace_id = ? and source_scope_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			firstContainerId,
		))

		mockMvc.delete("/api/github/repositories/$firstContainerId")
			.andExpect { status { isNoContent() } }
		mockMvc.post("/api/github/repositories/$firstContainerId/imports") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-02-01T00:00:00Z\"}"
		}.andExpect { status { isConflict() }; jsonPath("$.error") { value("REPOSITORY_INACTIVE") } }
	}

	@Test
	fun userDisconnectPersistsItsReasonAndDoesNotQueueAutomaticRecovery() {
		val connectionId = completeInstallation()
		val scopeId = connect(connectionId, 1001)

		mockMvc.delete("/api/github/repositories/$scopeId")
			.andExpect { status { isNoContent() } }

		mockMvc.get("/api/github/connections")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].repositories[0].status") { value("DISABLED") }
				jsonPath("$[0].repositories[0].statusReason") { value("USER_DISCONNECTED") }
			}

		mockMvc.post("/api/github/repositories/$scopeId/access-check?trigger=CHECK_AGAIN")
			.andExpect { status { isConflict() }; jsonPath("$.error") { value("REPOSITORY_DISCONNECTED") } }
	}

	@Test
	fun listsCurrentInstallationGrantWithExistingScopeStatus() {
		val connectionId = completeInstallation()
		val scopeId = connect(connectionId, 1001)

		mockMvc.get("/api/github/connections/$connectionId/repositories")
			.andExpect {
				status { isOk() }
				jsonPath("$.length()") { value(2) }
				jsonPath("\$[0].externalRepositoryId") { value(1001) }
				jsonPath("\$[0].id") { value(scopeId.toString()) }
				jsonPath("\$[0].status") { value("ACTIVE") }
				jsonPath("\$[1].externalRepositoryId") { value(1002) }
				jsonPath("\$[1].id") { doesNotExist() }
			}

		val foreignConnectionId = UUID.randomUUID()
		mockMvc.get("/api/github/connections/$foreignConnectionId/repositories")
			.andExpect { status { isNotFound() } }
	}

	@Test
	fun queuesAnExplicitRepositoryAccessRecheckWithoutChangingStateSynchronously() {
		val connectionId = completeInstallation()
		val scopeId = connect(connectionId, 1001)

		mockMvc.post("/api/github/repositories/$scopeId/access-check?trigger=CHECK_AGAIN")
			.andExpect {
				status { isAccepted() }
				header { string("Cache-Control", "no-store") }
				jsonPath("$.sourceScopeId") { value(scopeId.toString()) }
				jsonPath("$.status") { value("QUEUED") }
			}

		assertEquals("CHECK_AGAIN", jdbcTemplate.queryForObject(
			"select trigger from github_repository_access_checks where workspace_id = ? and source_scope_id = ?",
			String::class.java,
			devContext.devWorkspaceId,
			scopeId,
		))
	}

	@Test
	fun connectedRepositoryMarksWebhookMonitoringActiveWithoutStartingArtifactWorkflow() {
		val connectionId = completeInstallation()
		val response = mockMvc.put("/api/github/repositories/1001") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"connectionId\":\"$connectionId\"}"
		}.andExpect {
			status { isOk() }
			jsonPath("$.monitoring.status") { value("ACTIVE") }
			jsonPath("$.monitoring.analysisStatus") { value("QUEUED") }
		}.andReturn().response.contentAsString
		val scopeId = UUID.fromString(Regex("\"id\":\"([^\"]+)\"").find(response)!!.groupValues[1])

		val capabilities = jdbcTemplate.queryForObject(
			"""select b.capabilities::text from connection_namespace_bindings b
				join source_scopes s on s.workspace_id = b.workspace_id and s.source_namespace_id = b.source_namespace_id
				where s.workspace_id = ? and s.id = ?""".trimIndent(),
			String::class.java,
			devContext.devWorkspaceId,
			scopeId,
		)!!
		assertTrue(capabilities.contains("\"metadata\": \"read\""))
		assertTrue(capabilities.contains("\"pull_requests\": \"read\""))
		assertTrue(capabilities.contains("\"contents\": \"read\""))
		assertTrue(capabilities.contains("\"webhook_monitoring\": \"active\""))
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from github_repository_monitoring where workspace_id = ? and source_scope_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			scopeId,
		))
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from generation_runs where workspace_id = ?", Int::class.java, devContext.devWorkspaceId))
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from model_invocations", Int::class.java))
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from content_packs where workspace_id = ?", Int::class.java, devContext.devWorkspaceId))
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from github_release_draft_requests where workspace_id = ?", Int::class.java, devContext.devWorkspaceId))
	}

	@Test
	fun monitoringCompletesFromPublishedReleasesAndManualRetryReusesTheSameRow() {
		fakeClient.releaseTags = listOf("v1.0.0", "v1.1.0")
		val connectionId = completeInstallation()
		val scopeId = connect(connectionId, 1001)

		for (ignored in 1..100) {
			val status = jdbcTemplate.queryForObject(
				"select analysis_status from github_repository_monitoring where workspace_id = ? and source_scope_id = ?",
				String::class.java,
				devContext.devWorkspaceId,
				scopeId,
			)
			if (status == "COMPLETED") break
			Thread.sleep(20)
		}
		mockMvc.get("/api/github/repositories/$scopeId/monitoring")
			.andExpect {
				status { isOk() }
				header { string("Cache-Control", "no-store") }
				jsonPath("$.status") { value("ACTIVE") }
				jsonPath("$.analysisStatus") { value("COMPLETED") }
				jsonPath("$.releaseConvention") { value("SEMVER_V") }
				jsonPath("$.sampleSource") { value("RELEASES") }
				jsonPath("$.sampleSize") { value(2) }
			}
		assertEquals(0, fakeClient.tagListCalls.get())

		jdbcTemplate.update(
			"""
			update github_repository_monitoring
			set analysis_status = 'FAILED', release_convention = null, sample_source = null,
			    tag_prefix = null, last_error_code = 'MONITORING_ANALYSIS_FAILED', analyzed_at = null
			where workspace_id = ? and source_scope_id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
			scopeId,
		)
		mockMvc.post("/api/github/repositories/$scopeId/monitoring/retry")
			.andExpect {
				status { isAccepted() }
				jsonPath("$.analysisStatus") { value("QUEUED") }
				jsonPath("$.attemptCount") { value(0) }
			}
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from github_repository_monitoring where workspace_id = ? and source_scope_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			scopeId,
		))
	}

	@Test
	fun staleMonitoringClaimFailsAfterTheMaximumAttempt() {
		val scopeId = connect(completeInstallation(), 1001)
		jdbcTemplate.update(
			"""
			update github_repository_monitoring
			set analysis_status = 'ANALYZING', attempt_count = 5,
			    claimed_by = 'dead-worker', claimed_at = now() - interval '10 minutes'
			where workspace_id = ? and source_scope_id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
			scopeId,
		)

		monitoringWorker.recover()

		assertEquals(
			"FAILED:MONITORING_STALE_CLAIM_LIMIT",
			jdbcTemplate.queryForObject(
				"""
				select analysis_status || ':' || last_error_code
				from github_repository_monitoring
				where workspace_id = ? and source_scope_id = ?
				""".trimIndent(),
				String::class.java,
				devContext.devWorkspaceId,
				scopeId,
			),
		)
	}

	@Test
	fun missingInstallationIsPersistedAsNeedingReauthentication() {
		val connectionId = completeInstallation()
		fakeClient.repositoryFailureCode = "GITHUB_NOT_FOUND"

		mockMvc.get("/api/github/connections/$connectionId/repositories")
			.andExpect {
				status { isBadGateway() }
				jsonPath("$.error") { value("GITHUB_NOT_FOUND") }
			}

		assertEquals("NEEDS_REAUTH", jdbcTemplate.queryForObject(
			"select status from connections where workspace_id = ? and id = ?",
			String::class.java,
			devContext.devWorkspaceId,
			connectionId,
		))
	}

	@Test
	fun overlappingImportIsRejectedBeforeProviderWorkAndProviderFailureIsDurable() {
		val connectionId = completeInstallation()
		val containerId = connect(connectionId, 1001)
		insertRunningImport(containerId)
		mockMvc.post("/api/github/repositories/$containerId/imports") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-02-01T00:00:00Z\"}"
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("IMPORT_ALREADY_RUNNING") }
		}
		assertEquals(0, fakeClient.pullRequestCalls.get())

		jdbcTemplate.update("delete from source_imports where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_observations where workspace_id = ?", devContext.devWorkspaceId)
		fakeClient.failImports = true
		val response = mockMvc.post("/api/github/repositories/$containerId/imports") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-02-01T00:00:00Z\"}"
		}.andExpect {
			status { isBadGateway() }
			jsonPath("$.error") { value("IMPORT_FAILED") }
			jsonPath("$.resourceId") { exists() }
		}.andReturn().response.contentAsString
		val importId = UUID.fromString(Regex("\"resourceId\":\"([^\"]+)\"").find(response)!!.groupValues[1])
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from source_imports where workspace_id = ? and id = ?",
			String::class.java,
			devContext.devWorkspaceId,
			importId,
		))
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from writing_blocks where workspace_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
		))

		fakeClient.failImports = false
		fakeClient.invalidPayload = true
		mockMvc.post("/api/github/repositories/$containerId/imports") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-02-01T00:00:00Z\"}"
		}.andExpect {
			status { isBadGateway() }
			jsonPath("$.error") { value("IMPORT_FAILED") }
			jsonPath("$.resourceId") { exists() }
		}
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from writing_blocks where workspace_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
		))

		fakeClient.invalidPayload = false
		fakeClient.failImports = false
		fakeClient.failureCode = "GITHUB_ACCESS_DENIED"
		mockMvc.post("/api/github/repositories/$containerId/imports") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-02-01T00:00:00Z\"}"
		}.andExpect { status { isBadGateway() }; jsonPath("$.error") { value("GITHUB_ACCESS_DENIED") } }
		assertEquals("NEEDS_REAUTH", jdbcTemplate.queryForObject(
			"select status from connections where workspace_id = ? and id = ?",
			String::class.java,
			devContext.devWorkspaceId,
			connectionId,
		))
	}

	@Test
	fun foreignConnectionCannotTriggerProviderCalls() {
		val foreignId = UUID.randomUUID()
		mockMvc.put("/api/github/repositories/1001") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"connectionId\":\"$foreignId\"}"
		}.andExpect { status { isNotFound() } }
		assertEquals(0, fakeClient.repositoryListCalls.get())
	}

	@Test
	fun rejectsInvalidWindowsBeforeProviderAccess() {
		val connectionId = completeInstallation()
		val containerId = connect(connectionId, 1001)
		mockMvc.post("/api/github/repositories/$containerId/imports") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"from\":\"2026-02-01T00:00:00Z\",\"to\":\"2026-01-01T00:00:00Z\"}"
		}.andExpect { status { isBadRequest() }; jsonPath("$.error") { value("INVALID_IMPORT_WINDOW") } }
		mockMvc.post("/api/github/repositories/$containerId/imports") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"from\":\"2025-01-01T00:00:00Z\",\"to\":\"2026-02-01T00:00:00Z\"}"
		}.andExpect { status { isBadRequest() }; jsonPath("$.error") { value("IMPORT_WINDOW_TOO_LARGE") } }
		assertEquals(0, fakeClient.pullRequestCalls.get())
	}

	private fun completeInstallation(): UUID {
		val stateResponse = mockMvc.post("/api/github/installations/requests")
			.andReturn().response.contentAsString
		val state = Regex("\"state\":\"([^\"]+)\"").find(stateResponse)!!.groupValues[1]
		val response = mockMvc.post("/api/github/installations/callback") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"state\":\"$state\",\"installationId\":77}"
		}.andReturn().response.contentAsString
		return UUID.fromString(Regex("\"connectionId\":\"([^\"]+)\"").find(response)!!.groupValues[1])
	}

	private fun connect(connectionId: UUID, repositoryId: Long): UUID {
		val response = mockMvc.put("/api/github/repositories/$repositoryId") {
			contentType = MediaType.APPLICATION_JSON
			content = "{\"connectionId\":\"$connectionId\"}"
		}.andReturn().response.contentAsString
		return UUID.fromString(Regex("\"id\":\"([^\"]+)\"").find(response)!!.groupValues[1])
	}

	private fun insertRunningImport(scopeId: UUID) {
		val bindingId = jdbcTemplate.queryForObject(
			"""select b.id from connection_namespace_bindings b join source_scopes s
			       on s.workspace_id = b.workspace_id and s.source_namespace_id = b.source_namespace_id
			       where s.workspace_id = ? and s.id = ? and b.status = 'ACTIVE' limit 1""",
			UUID::class.java, devContext.devWorkspaceId, scopeId,
		)!!
		val observationId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into source_observations (
			 id, workspace_id, source_scope_id, binding_id, authority_owner, coverage_key,
			 observation_mode, generation, status, started_at, created_at
			) values (?, ?, ?, ?, ?, 'pull_requests', 'PARTIAL', 0, 'RUNNING', now(), now())
			""".trimIndent(), observationId, devContext.devWorkspaceId, scopeId, bindingId, "github:scope:$scopeId",
		)
		jdbcTemplate.update(
			"""
			insert into source_imports (
			  id, workspace_id, source_scope_id, observation_id, from_instant, to_instant, status,
			  started_at, created_at
			) values (?, ?, ?, ?, '2026-01-01T00:00:00Z', '2026-02-01T00:00:00Z', 'RUNNING', now(), now())
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			scopeId,
			observationId,
		)
	}

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun fakeGitHubClient() = FakeGitHubClient()
	}
}

class FakeGitHubClient : GitHubClient {
	val repositoryListCalls = AtomicInteger()
	val installationCalls = AtomicInteger()
	val providerCallObservedActiveTransaction = AtomicBoolean()
	val pullRequestCalls = AtomicInteger()
	val releaseListCalls = AtomicInteger()
	val tagListCalls = AtomicInteger()
	var installationAccount = GitHubInstallationAccount(9001, "acme", "User")
	var membershipRole: String? = "admin"
	var linkedIdentity = GitHubUserIdentity(9001, "acme")
	var releaseTags: List<String> = emptyList()
	var repositoryTags: List<String> = emptyList()
	var body = "Body"
	var updatedAt: Instant = Instant.parse("2026-01-02T00:00:00Z")
	var failImports = false
	var failureCode: String? = null
	var repositoryFailureCode: String? = null
	var invalidPayload = false

	private val repositories = listOf(
		GitHubRepository(1001, "acme", "one", "https://github.com/acme/one", "main"),
		GitHubRepository(1002, "acme", "two", "https://github.com/acme/two", "main"),
	)

	override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> {
		repositoryListCalls.incrementAndGet()
		providerCallObservedActiveTransaction.set(
			providerCallObservedActiveTransaction.get() || TransactionSynchronizationManager.isActualTransactionActive(),
		)
		repositoryFailureCode?.let {
			throw com.plot.api.common.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, it, "provider failure")
		}
		return repositories
	}

	var userInstallations: List<GitHubUserInstallation> = listOf(
		GitHubUserInstallation(installationId = 77, appId = "1", accountId = 9001, accountLogin = "acme", accountType = "User"),
	)

	override fun listUserInstallations(userAccessToken: String): List<GitHubUserInstallation> = userInstallations

	override fun getInstallation(installationId: Long): GitHubInstallation {
		installationCalls.incrementAndGet()
		return GitHubInstallation(installationId, installationAccount)
	}

	override fun resolveAuthenticatedUser(userAccessToken: String): GitHubUserIdentity = linkedIdentity

	override fun organizationMembershipRole(userAccessToken: String, org: String, username: String): String? = membershipRole

	override fun verifyRepositoryAccess(installationId: Long, repositoryId: Long, owner: String, repository: String): GitHubRepository {
		providerCallObservedActiveTransaction.set(
			providerCallObservedActiveTransaction.get() || TransactionSynchronizationManager.isActualTransactionActive(),
		)
		return repositories.first { it.id == repositoryId }
	}

	override fun listPublishedReleaseTags(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		limit: Int,
	): GitHubTagPage {
		releaseListCalls.incrementAndGet()
		return GitHubTagPage(releaseTags.take(limit), releaseTags.size > limit)
	}

	override fun listRepositoryTags(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		limit: Int,
	): GitHubTagPage {
		tagListCalls.incrementAndGet()
		return GitHubTagPage(repositoryTags.take(limit), repositoryTags.size > limit)
	}

	override fun listClosedPullRequests(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		pageCap: Int,
	): List<GitHubPullRequest> = listOf(
		GitHubPullRequest(
			id = 5001,
			number = 1,
			title = if (invalidPayload) "" else "Ship integration",
			body = if (invalidPayload) null else body,
			author = "ada",
			url = "https://github.com/acme/one/pull/1",
			baseBranch = "main",
			headBranch = "feature",
			createdAt = Instant.parse("2026-01-01T00:00:00Z"),
			updatedAt = updatedAt,
			mergedAt = Instant.parse("2026-01-10T00:00:00Z"),
		),
		GitHubPullRequest(
			id = 5002,
			number = 2,
			title = "At upper bound",
			body = "excluded",
			author = "ada",
			url = "https://github.com/acme/one/pull/2",
			baseBranch = "main",
			headBranch = "feature-2",
			createdAt = Instant.parse("2026-01-01T00:00:00Z"),
			updatedAt = Instant.parse("2026-02-01T00:00:00Z"),
			mergedAt = Instant.parse("2026-02-01T00:00:00Z"),
		),
		GitHubPullRequest(
			id = 5003,
			number = 3,
			title = "Unmerged",
			body = "excluded",
			author = "ada",
			url = "https://github.com/acme/one/pull/3",
			baseBranch = "main",
			headBranch = "feature-3",
			createdAt = Instant.parse("2026-01-01T00:00:00Z"),
			updatedAt = Instant.parse("2026-01-02T00:00:00Z"),
			mergedAt = null,
		),
		GitHubPullRequest(
			id = 5004,
			number = 4,
			title = "Before lower bound",
			body = "excluded",
			author = "ada",
			url = "https://github.com/acme/one/pull/4",
			baseBranch = "main",
			headBranch = "feature-4",
			createdAt = Instant.parse("2025-12-01T00:00:00Z"),
			updatedAt = Instant.parse("2025-12-02T00:00:00Z"),
			mergedAt = Instant.parse("2025-12-31T00:00:00Z"),
		),
	).also {
		pullRequestCalls.incrementAndGet()
		if (failImports) throw IllegalStateException("provider failure")
		failureCode?.let { throw com.plot.api.common.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, it, "provider failure") }
	}

	fun reset() {
		repositoryListCalls.set(0)
		installationCalls.set(0)
		installationAccount = GitHubInstallationAccount(9001, "acme", "User")
		membershipRole = "admin"
		linkedIdentity = GitHubUserIdentity(9001, "acme")
		providerCallObservedActiveTransaction.set(false)
		pullRequestCalls.set(0)
		releaseListCalls.set(0)
		tagListCalls.set(0)
		releaseTags = emptyList()
		repositoryTags = emptyList()
		body = "Body"
		updatedAt = Instant.parse("2026-01-02T00:00:00Z")
		userInstallations = listOf(
			GitHubUserInstallation(installationId = 77, appId = "1", accountId = 9001, accountLogin = "acme", accountType = "User"),
		)
		failImports = false
		failureCode = null
		repositoryFailureCode = null
		invalidPayload = false
	}
}
