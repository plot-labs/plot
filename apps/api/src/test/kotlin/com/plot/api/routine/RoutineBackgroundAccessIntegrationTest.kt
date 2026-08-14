package com.plot.api.routine

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.ApiException
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.artifact.workflow.ArtifactWorkflowRunWorker
import com.plot.api.github.GitHubClient
import com.plot.api.github.GitHubPullRequest
import com.plot.api.github.GitHubPullRequestPage
import com.plot.api.github.GitHubRepository
import com.plot.api.writingblock.WritingBlockRepository
import com.plot.api.persistence.JooqTransactionExecutor
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Import(TestcontainersConfiguration::class, RoutineBackgroundTestConfig::class)
@ActiveProfiles("test")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.routines.poll-delay=PT1H",
	"plot.routine-agent.workers-enabled=true",
	"plot.routine-agent.poll-delay=PT1H",
	"plot.routine-agent.retry-initial-delay=PT0S",
	"plot.github.enabled=true",
	"plot.github.dev-only=true",
	"plot.github.app-id=test-app",
	"plot.github.app-slug=test-app",
	"plot.github.private-key=test-private-key",
	"plot.github.state-secret=test-state-secret",
	"server.address=127.0.0.1",
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RoutineBackgroundAccessIntegrationTest {
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var routinePersistence: RoutinePersistence
	@Autowired private lateinit var agentPersistence: RoutineAgentPersistence
	@Autowired private lateinit var worker: RoutineWorker
	@Autowired private lateinit var githubClient: RoutineRefreshGitHubClient
	@Autowired private lateinit var writingBlockRepository: WritingBlockRepository
	@Autowired private lateinit var evidenceBudget: RoutineEvidenceBudget
	@Autowired private lateinit var transactionExecutor: JooqTransactionExecutor
	@Autowired private lateinit var agentProperties: RoutineAgentProperties
	@Autowired private lateinit var workspaceAccessService: WorkspaceAccessService
	@Autowired private lateinit var refreshService: GitHubRoutineRefreshService
	@Autowired private lateinit var objectMapper: ObjectMapper

	private val fixtures = mutableListOf<RefreshFixture>()

	@BeforeEach
	fun setUp() {
		devBootstrapService.bootstrap()
		setWorkspaceAccess("active", "full")
		githubClient.reset()
	}

	@AfterEach
	fun cleanUp() {
		fixtures.reversed().forEach(::deleteFixture)
		fixtures.clear()
		githubClient.reset()
		setWorkspaceAccess("active", "full")
	}

	@Test
	fun `scheduled execution refreshes GitHub before preflight and logs only safe metadata`() {
		val dueAt = Instant.now().minusSeconds(2)
		val fixture = insertFixture(dueAt)
		val secret = "SUPER_SECRET_SOURCE_BODY"
		githubClient.enqueue(GitHubPullRequestPage(listOf(pullRequest(101, dueAt.minusSeconds(60), secret)), null))
		val logger = LoggerFactory.getLogger(GitHubRoutineRefreshService::class.java) as Logger
		val appender = ListAppender<ILoggingEvent>().apply { start() }
		logger.addAppender(appender)

		try {
			assertTrue(worker.drain())
		} finally {
			logger.detachAppender(appender)
			appender.stop()
		}

		val execution = execution(fixture.routineId)
		assertEquals(RoutineExecutionStatus.DISPATCHED, execution.status)
		assertNotNull(execution.refreshCompletedAt)
		assertEquals(listOf("<first>"), githubClient.continuations)
		assertEquals(1, count("agent_runs", "routine_id", fixture.routineId))
		assertEquals(1, seedCount(execution.id))
		val logs = appender.list.joinToString("\n") { it.formattedMessage }
		assertTrue(logs.contains("state=COMPLETED"))
		assertFalse(logs.contains(secret))
		assertFalse(logs.contains("Authorization"))
		assertFalse(logs.contains("token", ignoreCase = true))
	}

	@Test
	fun `failed page resumes the same refresh without cursor movement or false no activity`() {
		val dueAt = Instant.now().minusSeconds(2)
		val fixture = insertFixture(dueAt)
		val continuation = "https://api.github.com/repos/acme/plot/pulls?state=closed&per_page=100&page=2"
		githubClient.enqueue(GitHubPullRequestPage(listOf(pullRequest(201, dueAt.minusSeconds(120))), continuation))
		githubClient.enqueue(ApiException(
			HttpStatus.SERVICE_UNAVAILABLE,
			"GITHUB_PROVIDER_UNAVAILABLE",
			"GitHub is temporarily unavailable",
		))
		githubClient.enqueue(GitHubPullRequestPage(listOf(pullRequest(202, dueAt.minusSeconds(60))), null))

		assertTrue(worker.drain())
		val executionId = routinePersistence.find(devContext.devWorkspaceId, fixture.routineId)?.lastExecutionId
		assertNotNull(executionId)
		val afterFirstPage = assertNotNull(agentPersistence.findExecution(devContext.devWorkspaceId, executionId))
		assertEquals(RoutineExecutionStatus.PROBING, afterFirstPage.status)
		assertTrue(afterFirstPage.refreshContinuationJson.orEmpty().contains(continuation))
		assertNull(routinePersistence.find(devContext.devWorkspaceId, fixture.routineId)?.activityCursorSequence)
		assertEquals(0, count("agent_runs", "routine_id", fixture.routineId))

		assertTrue(worker.drain())
		val afterFailure = assertNotNull(agentPersistence.findExecution(devContext.devWorkspaceId, executionId))
		assertEquals(RoutineExecutionStatus.PROBING, afterFailure.status)
		assertEquals("ROUTINE_REFRESH_RETRY", afterFailure.errorCode)
		assertNull(afterFailure.refreshCompletedAt)
		assertNull(routinePersistence.find(devContext.devWorkspaceId, fixture.routineId)?.activityCursorSequence)
		assertEquals(0, count("agent_runs", "routine_id", fixture.routineId))

		assertTrue(worker.drain())
		val completed = assertNotNull(agentPersistence.findExecution(devContext.devWorkspaceId, executionId))
		assertEquals(RoutineExecutionStatus.DISPATCHED, completed.status)
		assertNotNull(completed.refreshCompletedAt)
		assertEquals(2, seedCount(executionId))
		assertNotNull(routinePersistence.find(devContext.devWorkspaceId, fixture.routineId)?.activityCursorSequence)
		assertEquals(listOf("<first>", continuation, continuation), githubClient.continuations)
	}

	@Test
	fun `read only workspace blocks refresh and agent admission`() {
		val fixture = insertFixture(Instant.now().minusSeconds(2))
		githubClient.enqueue(GitHubPullRequestPage(emptyList(), null))
		setWorkspaceAccess("revoked", "read_only")

		assertTrue(worker.drain())

		val execution = execution(fixture.routineId)
		assertEquals(RoutineExecutionStatus.FAILED, execution.status)
		assertEquals("WORKSPACE_READ_ONLY", execution.errorCode)
		assertEquals(0, githubClient.calls)
		assertEquals(0, count("agent_runs", "routine_id", fixture.routineId))
		assertEquals(0, generationCountForRoutine(fixture.routineId))
	}

	@Test
	fun `two database workers produce one refresh and one admitted AgentRun`() {
		val dueAt = Instant.now().minusSeconds(2)
		val fixture = insertFixture(dueAt)
		githubClient.enqueue(GitHubPullRequestPage(listOf(pullRequest(301, dueAt.minusSeconds(60))), null))
		val first = newWorker("routine-background-a")
		val second = newWorker("routine-background-b")
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)

		val results = try {
			listOf(first, second).map { candidate ->
				executor.submit<Boolean> {
					start.await()
					candidate.drain()
				}
			}.also { start.countDown() }.map { it.get() }
		} finally {
			executor.shutdownNow()
		}

		assertEquals(1, results.count { it })
		assertEquals(1, githubClient.calls)
		assertEquals(1, count("routine_executions", "routine_id", fixture.routineId))
		assertEquals(1, count("agent_runs", "routine_id", fixture.routineId))
	}

	private fun newWorker(workerId: String) = RoutineWorker(
		routinePersistence,
		agentPersistence,
		writingBlockRepository,
		evidenceBudget,
		transactionExecutor,
		agentProperties,
		workspaceAccessService,
		refreshService,
		objectMapper,
		workerId = workerId,
		claimTimeout = agentProperties.claimTimeout,
	)

	private fun insertFixture(dueAt: Instant): RefreshFixture {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val installationId = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
		val repositoryId = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
		jdbcTemplate.update(
			"insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())",
			connectionId,
			devContext.devWorkspaceId,
			installationId.toString(),
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at) values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'acme/plot', 'ACTIVE', now(), now())",
			namespaceId,
			devContext.devWorkspaceId,
			"repository:$repositoryId",
		)
		jdbcTemplate.update(
			"insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at) values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())",
			bindingId,
			devContext.devWorkspaceId,
			connectionId,
			namespaceId,
		)
		jdbcTemplate.update(
			"insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, external_key, display_name, metadata, status, status_changed_at, created_at, updated_at) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/plot', 'acme/plot', '{\"defaultBranch\":\"main\"}'::jsonb, 'ACTIVE', now(), now(), now())",
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			repositoryId.toString(),
		)
		val routine = routinePersistence.insert(
			devContext.devWorkspaceId,
			devContext.devUserId,
			"Background refresh ${UUID.randomUUID()}",
			scopeId,
			"Create a source-backed update",
			RoutineCadence.DAILY,
			dueAt,
		)
		return RefreshFixture(connectionId, namespaceId, bindingId, scopeId, routine.id).also(fixtures::add)
	}

	private fun pullRequest(id: Long, mergedAt: Instant, body: String = "Routine activity") = GitHubPullRequest(
		id = id,
		number = id.toInt(),
		title = "Pull request $id",
		body = body,
		author = "ada",
		url = "https://github.com/acme/plot/pull/$id",
		baseBranch = "main",
		headBranch = "feature-$id",
		createdAt = mergedAt.minusSeconds(3_600),
		updatedAt = mergedAt,
		mergedAt = mergedAt,
	)

	private fun execution(routineId: UUID): RoutineExecutionRecord {
		val id = assertNotNull(routinePersistence.find(devContext.devWorkspaceId, routineId)?.lastExecutionId)
		return assertNotNull(agentPersistence.findExecution(devContext.devWorkspaceId, id))
	}

	private fun seedCount(executionId: UUID): Int = jdbcTemplate.queryForObject(
		"select count(*) from agent_run_inputs input join agent_runs run on run.workspace_id = input.workspace_id and run.id = input.agent_run_id where run.routine_execution_id = ? and input.input_kind = 'SEED'",
		Int::class.java,
		executionId,
	) ?: 0

	private fun count(table: String, column: String, value: Any): Int = jdbcTemplate.queryForObject(
		"select count(*) from $table where $column = ?",
		Int::class.java,
		value,
	) ?: 0

	private fun generationCountForRoutine(routineId: UUID): Int = jdbcTemplate.queryForObject(
		"select count(*) from generation_runs where idempotency_key like ?",
		Int::class.java,
		"routine:$routineId:%",
	) ?: 0

	private fun setWorkspaceAccess(status: String, accessMode: String) {
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = ?, access_mode = ?, updated_at = now() where id = ?",
			status,
			accessMode,
			devContext.devWorkspaceId,
		)
	}

	private fun deleteFixture(fixture: RefreshFixture) {
		val workspaceId = devContext.devWorkspaceId
		jdbcTemplate.update("delete from agent_steps where workspace_id = ? and agent_run_id in (select id from agent_runs where routine_id = ?)", workspaceId, fixture.routineId)
		jdbcTemplate.update("delete from generation_inputs where workspace_id = ? and generation_run_id in (select id from generation_runs where agent_run_id in (select id from agent_runs where routine_id = ?))", workspaceId, fixture.routineId)
		jdbcTemplate.update("delete from generation_runs where workspace_id = ? and agent_run_id in (select id from agent_runs where routine_id = ?)", workspaceId, fixture.routineId)
		jdbcTemplate.update("delete from agent_run_inputs where workspace_id = ? and agent_run_id in (select id from agent_runs where routine_id = ?)", workspaceId, fixture.routineId)
		jdbcTemplate.update("delete from agent_run_sources where workspace_id = ? and agent_run_id in (select id from agent_runs where routine_id = ?)", workspaceId, fixture.routineId)
		jdbcTemplate.update("delete from agent_runs where workspace_id = ? and routine_id = ?", workspaceId, fixture.routineId)
		jdbcTemplate.update("delete from work_sessions where workspace_id = ? and routine_execution_id in (select id from routine_executions where workspace_id = ? and routine_id = ?)", workspaceId, workspaceId, fixture.routineId)
		jdbcTemplate.update("delete from routine_execution_evidence where workspace_id = ? and execution_id in (select id from routine_executions where routine_id = ?)", workspaceId, fixture.routineId)
		jdbcTemplate.update("delete from routine_executions where workspace_id = ? and routine_id = ?", workspaceId, fixture.routineId)
		jdbcTemplate.update("delete from routines where workspace_id = ? and id = ?", workspaceId, fixture.routineId)
		jdbcTemplate.update("delete from writing_block_scopes where workspace_id = ? and source_namespace_id = ?", workspaceId, fixture.namespaceId)
		jdbcTemplate.update("delete from writing_blocks where workspace_id = ? and source_namespace_id = ?", workspaceId, fixture.namespaceId)
		jdbcTemplate.update("delete from source_observations where workspace_id = ? and source_scope_id = ?", workspaceId, fixture.scopeId)
		jdbcTemplate.update("delete from source_scopes where workspace_id = ? and id = ?", workspaceId, fixture.scopeId)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ? and id = ?", workspaceId, fixture.bindingId)
		jdbcTemplate.update("delete from source_namespaces where workspace_id = ? and id = ?", workspaceId, fixture.namespaceId)
		jdbcTemplate.update("delete from connections where workspace_id = ? and id = ?", workspaceId, fixture.connectionId)
	}
}

@SpringBootTest
@Import(TestcontainersConfiguration::class, RoutineBackgroundTestConfig::class)
@ActiveProfiles("test")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.routines.poll-delay=PT1H",
	"plot.routine-agent.workers-enabled=false",
	"plot.routine-agent.poll-delay=PT1H",
	"server.address=127.0.0.1",
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RoutineWorkersDisabledIntegrationTest {
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var routinePersistence: RoutinePersistence
	@Autowired private lateinit var agentPersistence: RoutineAgentPersistence
	@Autowired private lateinit var worker: RoutineWorker
	@Autowired private lateinit var agentWorker: AgentRunWorker
	@Autowired private lateinit var artifactWorkflowWorker: ArtifactWorkflowRunWorker
	@Autowired private lateinit var githubClient: RoutineRefreshGitHubClient

	@Test
	fun `disabled workers leave due routines queued across two polls`() {
		devBootstrapService.bootstrap()
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = 'active', access_mode = 'full', updated_at = now() where id = ?",
			devContext.devWorkspaceId,
		)
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at) values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'ACTIVE', now(), now())",
			namespaceId,
			devContext.devWorkspaceId,
			"disabled:$namespaceId",
		)
		jdbcTemplate.update(
			"insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, display_name, status, status_changed_at, created_at, updated_at) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'Disabled', 'ACTIVE', now(), now(), now())",
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			"disabled:$scopeId",
		)
		val routine = routinePersistence.insert(
			devContext.devWorkspaceId,
			devContext.devUserId,
			"Disabled worker",
			scopeId,
			"Do not run",
			RoutineCadence.DAILY,
			Instant.now().minusSeconds(2),
		)
		val execution = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = devContext.devWorkspaceId,
				routineId = routine.id,
				createdByUserId = devContext.devUserId,
				triggerSourceScopeId = scopeId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "disabled:${routine.id}",
				requestFingerprint = "disabled:${routine.id}",
				activityCursorBefore = null,
			),
		)
		val chatId = UUID.randomUUID()
		val agentRunId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into work_sessions (id, workspace_id, title, status, created_by_user_id, last_activity_at, created_at, updated_at, routine_execution_id) values (?, ?, 'Routine: Disabled worker', 'OPEN', ?, now(), now(), now(), ?)",
			chatId,
			devContext.devWorkspaceId,
			devContext.devUserId,
			execution.id,
		)
		jdbcTemplate.update(
			"update routine_executions set status = 'DISPATCHED', finished_at = greatest(now(), created_at), updated_at = now() where id = ?",
			execution.id,
		)
		jdbcTemplate.update(
			"""
			insert into agent_runs (
			 id, workspace_id, routine_execution_id, routine_id, work_session_id, created_by_user_id,
			 origin, idempotency_key, request_fingerprint,
			 instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot,
			 status, max_attempts, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, 'ROUTINE', ?, ?, 'Do not run', 'test', 'read-only-v1', '{}'::jsonb, 'QUEUED', 3, now(), now())
			""".trimIndent(),
			agentRunId,
			devContext.devWorkspaceId,
			execution.id,
			routine.id,
			chatId,
			devContext.devUserId,
			"routine:$execution.id",
			"routine:$execution.id",
		)
		val artifactWorkflowRunId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into generation_runs (
			 id, workspace_id, agent_run_id, work_session_id, source_scope_id, created_by_user_id,
			 idempotency_key, request_fingerprint, status, workflow_version, prompt_version,
			 output_schema_version, budget_version, provider, model_name, budget_snapshot,
			 created_at, updated_at
			) values (?, ?, ?, ?, null, ?, ?, ?, 'QUEUED', 'fixed-v1', 'test', 'test', 'test', 'test', 'test', '{}'::jsonb, now(), now())
			""".trimIndent(),
			artifactWorkflowRunId,
			devContext.devWorkspaceId,
			agentRunId,
			chatId,
			devContext.devUserId,
			"disabled:$artifactWorkflowRunId",
			"disabled:$artifactWorkflowRunId",
		)
		val executionCountBefore = jdbcTemplate.queryForObject(
			"select count(*) from routine_executions where workspace_id = ? and routine_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			routine.id,
		)
		val agentCountBefore = jdbcTemplate.queryForObject(
			"select count(*) from agent_runs where workspace_id = ? and routine_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			routine.id,
		)

		try {
			assertFalse(worker.drain())
			assertFalse(worker.drain())
			assertFalse(agentWorker.processOne())
			assertFalse(agentWorker.processOne())
			assertFalse(artifactWorkflowWorker.processOne())
			assertFalse(artifactWorkflowWorker.processOne())
			val queued = assertNotNull(routinePersistence.find(devContext.devWorkspaceId, routine.id))
			assertNull(queued.claimedBy)
			assertNull(queued.activeExecutionId)
			assertNull(jdbcTemplate.queryForObject("select claimed_by from agent_runs where id = ?", String::class.java, agentRunId))
			assertEquals("QUEUED", jdbcTemplate.queryForObject("select status from agent_runs where id = ?", String::class.java, agentRunId))
			assertNull(jdbcTemplate.queryForObject("select claimed_by from generation_runs where id = ?", String::class.java, artifactWorkflowRunId))
			assertEquals("QUEUED", jdbcTemplate.queryForObject("select status from generation_runs where id = ?", String::class.java, artifactWorkflowRunId))
			assertEquals(executionCountBefore, jdbcTemplate.queryForObject(
				"select count(*) from routine_executions where workspace_id = ? and routine_id = ?",
				Int::class.java,
				devContext.devWorkspaceId,
				routine.id,
			))
			assertEquals(agentCountBefore, jdbcTemplate.queryForObject(
				"select count(*) from agent_runs where workspace_id = ? and routine_id = ?",
				Int::class.java,
				devContext.devWorkspaceId,
				routine.id,
			))
			assertEquals(0, githubClient.calls)
		} finally {
			jdbcTemplate.update("delete from generation_runs where id = ?", artifactWorkflowRunId)
			jdbcTemplate.update("delete from agent_runs where id = ?", agentRunId)
			jdbcTemplate.update("delete from work_sessions where workspace_id = ? and routine_execution_id = ?", devContext.devWorkspaceId, execution.id)
			jdbcTemplate.update("delete from routine_executions where id = ?", execution.id)
			jdbcTemplate.update("delete from routines where workspace_id = ? and id = ?", devContext.devWorkspaceId, routine.id)
			jdbcTemplate.update("delete from source_scopes where workspace_id = ? and id = ?", devContext.devWorkspaceId, scopeId)
			jdbcTemplate.update("delete from source_namespaces where workspace_id = ? and id = ?", devContext.devWorkspaceId, namespaceId)
		}
	}
}

@TestConfiguration(proxyBeanMethods = false)
class RoutineBackgroundTestConfig {
	@Bean
	@Primary
	fun routineRefreshGitHubClient() = RoutineRefreshGitHubClient()
}

class RoutineRefreshGitHubClient : GitHubClient {
	private val outcomes = ConcurrentLinkedQueue<Any>()
	val continuations = CopyOnWriteArrayList<String>()
	val calls: Int get() = continuations.size

	fun enqueue(value: Any) {
		outcomes.add(value)
	}

	fun reset() {
		outcomes.clear()
		continuations.clear()
	}

	override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> = error("not used")

	override fun listClosedPullRequests(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		pageCap: Int,
	): List<GitHubPullRequest> = error("paged refresh must be used")

	override fun listClosedPullRequestsPage(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		continuation: String?,
	): GitHubPullRequestPage {
		continuations += continuation ?: "<first>"
		return when (val outcome = outcomes.remove()) {
			is GitHubPullRequestPage -> outcome
			is ApiException -> throw outcome
			is RuntimeException -> throw outcome
			else -> error("Unsupported scripted GitHub outcome")
		}
	}
}

private data class RefreshFixture(
	val connectionId: UUID,
	val namespaceId: UUID,
	val bindingId: UUID,
	val scopeId: UUID,
	val routineId: UUID,
)
