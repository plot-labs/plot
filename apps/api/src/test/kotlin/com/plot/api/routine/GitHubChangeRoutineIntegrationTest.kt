package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.github.GitHubWebhookCommit
import com.plot.api.github.GitHubWebhookService
import com.plot.api.github.ParsedGitHubWebhook
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class, GitHubChangeRoutineIntegrationTest.Config::class)
@TestPropertySource(properties = [
	"plot.routines.schedule-scan-delay=PT1H",
	"plot.routine-agent.workers-enabled=true",
])
class GitHubChangeRoutineIntegrationTest {
	@Autowired private lateinit var webhookService: GitHubWebhookService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var routineWorker: RoutineWorker
	@Autowired private lateinit var routinePersistence: RoutinePersistence
	@Autowired private lateinit var agentPersistence: RoutineAgentPersistence
	private val routineIds = mutableListOf<UUID>()
	private val repositories = mutableListOf<RepositoryFixture>()
	private val deliveries = mutableListOf<String>()

	@BeforeEach
	fun bootstrap() {
		devBootstrapService.bootstrap()
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = 'active', access_mode = 'full', updated_at = now() where id = ?",
			devContext.devWorkspaceId,
		)
	}

	@AfterEach
	fun removeFixtures() {
		val workspaceId = devContext.devWorkspaceId
		jdbcTemplate.update("delete from agent_steps where workspace_id = ?", workspaceId)
		jdbcTemplate.update("delete from generation_runs where workspace_id = ? and agent_run_id is not null", workspaceId)
		jdbcTemplate.update("delete from agent_run_inputs where workspace_id = ?", workspaceId)
		jdbcTemplate.update("delete from agent_run_sources where workspace_id = ?", workspaceId)
		jdbcTemplate.update("delete from agent_runs where workspace_id = ?", workspaceId)
		jdbcTemplate.update("delete from work_sessions where workspace_id = ? and routine_execution_id is not null", workspaceId)
		jdbcTemplate.update("delete from routine_execution_evidence where workspace_id = ?", workspaceId)
		jdbcTemplate.update("delete from routine_executions where workspace_id = ?", workspaceId)
		routineIds.forEach { routineId -> jdbcTemplate.update("delete from routines where id = ?", routineId) }
		deliveries.forEach { externalDeliveryId ->
			jdbcTemplate.update("delete from github_webhook_deliveries where external_delivery_id = ?", externalDeliveryId)
		}
		repositories.forEach { repository ->
			jdbcTemplate.update("delete from writing_block_scopes where source_namespace_id = ?", repository.namespaceId)
			jdbcTemplate.update("delete from writing_blocks where source_namespace_id = ?", repository.namespaceId)
			jdbcTemplate.update("delete from source_observations where workspace_id = ? and authority_owner like 'GITHUB_%'", workspaceId)
			jdbcTemplate.update("delete from source_scopes where id = ?", repository.scopeId)
			jdbcTemplate.update("delete from connection_namespace_bindings where id = ?", repository.bindingId)
			jdbcTemplate.update("delete from source_namespaces where id = ?", repository.namespaceId)
			jdbcTemplate.update("delete from connections where id = ?", repository.connectionId)
		}
		routineIds.clear()
		repositories.clear()
		deliveries.clear()
	}

	@Test
	fun `default branch delivery creates one canonical execution and one AgentRun`() {
		val repository = bindRepository()
		val routineId = insertRoutine(repository.scopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val sha = "b".repeat(40)

		val webhook = pushWebhook(deliveryId, sha, "Ship routines\n\nGenerate a draft on push.", "c".repeat(64))
		webhookService.accept(webhook)
		webhookService.accept(webhook)

		assertEquals(1, countExecutions(routineId))
		drainOne()
		val executionId = executionId(routineId, deliveryId)
		assertEquals(RoutineExecutionStatus.DISPATCHED, executionStatus(executionId))
		assertEquals("github:$routineId:${deliveryUuid(deliveryId)}", triggerKey(executionId))
		assertEquals(1, count("work_sessions"))
		assertEquals(1, count("agent_runs"))
		assertEquals(1, seedCount(executionId))
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from generation_runs where workspace_id = ? and agent_run_id is not null",
			Int::class.java,
			devContext.devWorkspaceId,
		))
		assertEquals("QUEUED", jdbcTemplate.queryForObject(
			"select disposition from github_webhook_deliveries where external_delivery_id = ?",
			String::class.java,
			deliveryId,
		))
		assertEquals("Ship routines", jdbcTemplate.queryForObject(
			"select title from writing_blocks where workspace_id = ? and external_object_key = ?",
			String::class.java,
			devContext.devWorkspaceId,
			"commit:$sha",
		))
	}

	@Test
	fun `published release and tag deliveries create the matching canonical trigger`() {
		val repository = bindRepository()
		val releaseRoutineId = insertRoutine(repository.scopeId, RoutineCadence.ON_GITHUB_RELEASE)
		val releaseDeliveryId = "release-${UUID.randomUUID()}"
		deliveries += releaseDeliveryId
		webhookService.accept(ParsedGitHubWebhook(
			externalDeliveryId = releaseDeliveryId,
			eventType = "release",
			eventAction = "published",
			installationId = repository.installationId,
			repositoryId = repository.repositoryId,
			ref = null,
			beforeSha = null,
			afterSha = null,
			tagName = "v2.0.0",
			refCreated = null,
			refDeleted = null,
			forced = null,
			payloadHash = "d".repeat(64),
		))
		assertEquals(1, countExecutions(releaseRoutineId))
		drainOne()
		assertEquals(RoutineExecutionStatus.DISPATCHED, executionStatus(executionId(releaseRoutineId, releaseDeliveryId)))

		val tagRoutineId = insertRoutine(repository.scopeId, RoutineCadence.ON_GIT_TAG)
		val tagDeliveryId = "tag-${UUID.randomUUID()}"
		deliveries += tagDeliveryId
		webhookService.accept(ParsedGitHubWebhook(
			externalDeliveryId = tagDeliveryId,
			eventType = "push",
			eventAction = null,
			installationId = repository.installationId,
			repositoryId = repository.repositoryId,
			ref = "refs/tags/v2.0.0",
			beforeSha = "0".repeat(40),
			afterSha = "e".repeat(40),
			tagName = "v2.0.0",
			refCreated = true,
			refDeleted = false,
			forced = false,
			payloadHash = "f".repeat(64),
		))
		assertEquals(1, countExecutions(tagRoutineId))
		drainOne()
		assertEquals(RoutineExecutionStatus.DISPATCHED, executionStatus(executionId(tagRoutineId, tagDeliveryId)))
	}

	@Test
	fun `tag evidence keeps one event item within the central batch budget`() {
		val repository = bindRepository()
		val routineId = insertRoutine(repository.scopeId, RoutineCadence.ON_GIT_TAG)
		val deliveryId = "tag-budget-${UUID.randomUUID()}"
		val commits = (1..25).map { index ->
			val sha = index.toString(16).padStart(40, '0')
			GitHubWebhookCommit(
				sha = sha,
				message = "Commit $index",
				author = "octocat",
				timestamp = Instant.parse("2026-08-09T00:00:00Z").plusSeconds(index.toLong()),
				url = "https://github.com/acme/repo/commit/$sha",
			)
		}
		webhookService.accept(ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "push",
			eventAction = null,
			installationId = repository.installationId,
			repositoryId = repository.repositoryId,
			ref = "refs/tags/v3.0.0",
			beforeSha = "0".repeat(40),
			afterSha = "f".repeat(40),
			tagName = "v3.0.0",
			refCreated = true,
			refDeleted = false,
			forced = false,
			commits = commits,
			payloadHash = "1".repeat(64),
		))

		assertEquals(20, evidenceCount(routineId, deliveryId))
		drainOne()
		assertEquals(20, seedCount(executionId(routineId, deliveryId)))
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from routine_execution_evidence evidence join writing_blocks block on block.id = evidence.writing_block_id where evidence.execution_id = ? and block.source_kind = 'tag'",
			Int::class.java,
			executionId(routineId, deliveryId),
		))
	}

	@Test
	fun `same external evidence in a new delivery is no activity`() {
		val repository = bindRepository()
		val routineId = insertRoutine(repository.scopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val sha = "4".repeat(40)
		val firstDeliveryId = "first-${UUID.randomUUID()}"
		val secondDeliveryId = "second-${UUID.randomUUID()}"
		webhookService.accept(pushWebhook(firstDeliveryId, sha, "Original evidence", "a".repeat(64)))
		drainOne()
		webhookService.accept(pushWebhook(secondDeliveryId, sha, "Original evidence", "a".repeat(64)))
		assertEquals(2, countExecutions(routineId))
		drainOne()
		assertEquals(RoutineExecutionStatus.NO_ACTIVITY, executionStatus(executionId(routineId, secondDeliveryId)))
		assertEquals(1, count("work_sessions"))
		assertEquals(1, count("agent_runs"))
	}

	@Test
	fun `GitHub evidence does not skip older unconsumed activity or duplicate it on manual run`() {
		val repository = bindRepository()
		val routineId = insertRoutine(repository.scopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val olderBlockId = insertBlock(repository, "older-${UUID.randomUUID()}", "Older unconsumed activity")
		val deliveryId = "cursor-${UUID.randomUUID()}"

		webhookService.accept(pushWebhook(deliveryId, "8".repeat(40), "New event activity", "8".repeat(64)))
		drainOne()
		assertEquals(null, routinePersistence.find(devContext.devWorkspaceId, routineId)?.activityCursorSequence)
		jdbcTemplate.update(
			"update agent_runs set status = 'SUCCEEDED', finished_at = now(), updated_at = now() where routine_execution_id = ?",
			executionId(routineId, deliveryId),
		)

		val routine = assertNotNull(routinePersistence.find(devContext.devWorkspaceId, routineId))
		val manual = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = routine.workspaceId,
				routineId = routine.id,
				createdByUserId = routine.createdByUserId,
				triggerSourceScopeId = routine.sourceScopeId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "manual:${routine.id}:after-event",
				requestFingerprint = "manual-after-event",
				activityCursorBefore = routine.activityCursorSequence,
			),
		)
		routineWorker.runNow(routine.workspaceId, routine.id, manual.id)

		assertEquals(listOf(olderBlockId), seedIds(manual.id))
		assertEquals(2, count("work_sessions"))
		assertEquals(2, count("agent_runs"))
	}

	@Test
	fun `stale canonical claim is recovered without duplicating the AgentRun`() {
		val repository = bindRepository()
		val routineId = insertRoutine(repository.scopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val deliveryId = "stale-${UUID.randomUUID()}"
		webhookService.accept(pushWebhook(deliveryId, "7".repeat(40), "Recover after restart", "3".repeat(64)))
		val executionId = executionId(routineId, deliveryId)
		val firstClaim = assertNotNull(agentPersistence.claimById(
			"dead-worker",
			devContext.devWorkspaceId,
			executionId,
			Instant.parse("2026-08-09T00:00:00Z"),
			Instant.parse("2026-08-08T23:58:00Z"),
		))
		jdbcTemplate.update(
			"update routine_executions set claimed_at = ? where id = ?",
			Timestamp.from(Instant.parse("2026-08-08T23:40:00Z")),
			executionId,
		)
		assertEquals(firstClaim.id, executionId)
		drainOne()
		val recovered = assertNotNull(agentPersistence.findExecution(devContext.devWorkspaceId, executionId))
		assertEquals(2, recovered.attemptCount)
		assertEquals(RoutineExecutionStatus.DISPATCHED, recovered.status)
		assertEquals(1, count("work_sessions"))
		assertEquals(1, count("agent_runs"))
	}

	@Test
	fun `one routine admission failure does not roll back a matching routine`() {
		val repository = bindRepository()
		val firstRoutineId = insertRoutine(repository.scopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val secondRoutineId = insertRoutine(repository.scopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val contextNamespaceId = UUID.randomUUID()
		val contextScopeId = UUID.randomUUID()
		insertContextScope(contextNamespaceId, contextScopeId)
		val deliveryId = "isolated-${UUID.randomUUID()}"
		webhookService.accept(pushWebhook(deliveryId, "9".repeat(40), "Independent routine jobs", "2".repeat(64)))
		assertEquals("QUEUED", jdbcTemplate.queryForObject(
			"select disposition from github_webhook_deliveries where external_delivery_id = ?",
			String::class.java,
			deliveryId,
		))
		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from routine_executions where workspace_id = ? and trigger_delivery_id = (select id from github_webhook_deliveries where external_delivery_id = ?)",
			Int::class.java,
			devContext.devWorkspaceId,
			deliveryId,
		))
		jdbcTemplate.update(
			"insert into routine_context_sources (id, workspace_id, routine_id, source_scope_id, order_index, created_at) values (?, ?, ?, ?, 0, now())",
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			firstRoutineId,
			contextScopeId,
		)
		jdbcTemplate.update("update source_scopes set status = 'ERROR' where id = ?", contextScopeId)
		drainOne()
		drainOne()
		assertEquals(RoutineExecutionStatus.FAILED, executionStatus(executionId(firstRoutineId, deliveryId)))
		assertEquals(RoutineExecutionStatus.DISPATCHED, executionStatus(executionId(secondRoutineId, deliveryId)))
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from agent_runs where workspace_id = ? and routine_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			secondRoutineId,
		))
	}

	@Test
	fun `older projection cannot overwrite a newer canonical execution`() {
		val repository = bindRepository()
		val routineId = insertRoutine(repository.scopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val firstDeliveryId = "projection-first-${UUID.randomUUID()}"
		val secondDeliveryId = "projection-second-${UUID.randomUUID()}"
		webhookService.accept(pushWebhook(firstDeliveryId, "5".repeat(40), "First event", "5".repeat(64)))
		webhookService.accept(pushWebhook(secondDeliveryId, "6".repeat(40), "Second event", "6".repeat(64)))
		val firstExecutionId = executionId(routineId, firstDeliveryId)
		val secondExecutionId = executionId(routineId, secondDeliveryId)
		val newerAt = Instant.parse("2026-08-09T01:00:00Z")
		agentPersistence.projectRoutine(
			devContext.devWorkspaceId,
			routineId,
			secondExecutionId,
			newerAt,
			newerAt.plusSeconds(3600),
			"NO_ACTIVITY",
			projectionAt = newerAt,
		)
		agentPersistence.projectRoutine(
			devContext.devWorkspaceId,
			routineId,
			firstExecutionId,
			newerAt.plusSeconds(1),
			newerAt.plusSeconds(3600),
			"FAILED",
			projectionAt = newerAt.minusSeconds(1),
		)
		assertEquals(secondExecutionId, jdbcTemplate.queryForObject(
			"select last_execution_id from routines where id = ?",
			UUID::class.java,
			routineId,
		))
	}

	private fun pushWebhook(
		deliveryId: String,
		sha: String,
		message: String,
		payloadHash: String,
	): ParsedGitHubWebhook = repositories.first { it.installationId != 0L }.let { repository ->
		deliveries += deliveryId
		ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "push",
			eventAction = null,
			installationId = repository.installationId,
			repositoryId = repository.repositoryId,
			ref = "refs/heads/main",
			beforeSha = "0".repeat(40),
			afterSha = sha,
			tagName = null,
			refCreated = false,
			refDeleted = false,
			forced = false,
			commits = listOf(GitHubWebhookCommit(
				sha = sha,
				message = message,
				author = "octocat",
				timestamp = Instant.parse("2026-08-09T00:00:00Z"),
				url = "https://github.com/acme/repo/commit/$sha",
			)),
			payloadHash = payloadHash,
		)
	}

	private fun insertRoutine(sourceScopeId: UUID, cadence: RoutineCadence): UUID = UUID.randomUUID().also { routineId ->
		routineIds += routineId
		jdbcTemplate.update(
			"""
			insert into routines (
			 id, workspace_id, created_by_user_id, source_scope_id, name, instruction, cadence,
			 enabled, next_run_at, created_at, updated_at
			) values (?, ?, ?, ?, 'GitHub update', 'Draft this GitHub event', ?, true, now(), now(), now())
			""".trimIndent(),
			routineId,
			devContext.devWorkspaceId,
			devContext.devUserId,
			sourceScopeId,
			cadence.name,
		)
	}

	private fun insertContextScope(namespaceId: UUID, scopeId: UUID) {
		jdbcTemplate.update(
			"insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at) values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'Context', 'ACTIVE', now(), now())",
			namespaceId,
			devContext.devWorkspaceId,
			"context:$namespaceId",
		)
		jdbcTemplate.update(
			"insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, external_key, display_name, status, created_at, updated_at) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/context', 'Context', 'ACTIVE', now(), now())",
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			"context:$scopeId",
		)
		repositories += RepositoryFixture(UUID.randomUUID(), namespaceId, UUID.randomUUID(), scopeId, 0, 0)
	}

	private fun insertBlock(repository: RepositoryFixture, externalKey: String, title: String): UUID =
		UUID.randomUUID().also { blockId ->
			jdbcTemplate.update(
				"""
				insert into writing_blocks (
				 id, workspace_id, source_namespace_id, external_object_key, source_origin, source_kind,
				 title, body, url, canonical_url, platform, content_hash, ingested_at, status, created_at, updated_at
				) values (?, ?, ?, ?, 'integration', 'commit', ?, null, ?, ?, 'github', ?, now(), 'ACTIVE', now(), now())
				""".trimIndent(),
				blockId,
				devContext.devWorkspaceId,
				repository.namespaceId,
				externalKey,
				title,
				"https://github.com/acme/repo/commit/$blockId",
				"https://github.com/acme/repo/commit/$blockId",
				"hash-$blockId",
			)
			jdbcTemplate.update(
				"""
				insert into writing_block_scopes (
				 id, workspace_id, source_namespace_id, writing_block_id, source_scope_id,
				 membership_kind, status, first_seen_at, last_seen_at
				) values (?, ?, ?, ?, ?, 'CONTAINED_IN', 'ACTIVE', now(), now())
				""".trimIndent(),
				UUID.randomUUID(),
				devContext.devWorkspaceId,
				repository.namespaceId,
				blockId,
				repository.scopeId,
			)
		}

	private fun bindRepository(): RepositoryFixture {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val installationId = randomPositiveLong()
		val repositoryId = randomPositiveLong()
		jdbcTemplate.update(
			"insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())",
			connectionId,
			devContext.devWorkspaceId,
			installationId.toString(),
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at) values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'ACTIVE', now(), now())",
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
			"insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, external_key, display_name, metadata, status, created_at, updated_at) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, 'acme/repo', '{\"defaultBranch\":\"main\"}'::jsonb, 'ACTIVE', now(), now())",
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			repositoryId.toString(),
			"acme/repo-$repositoryId",
		)
		return RepositoryFixture(connectionId, namespaceId, bindingId, scopeId, installationId, repositoryId).also(repositories::add)
	}

	private fun countExecutions(routineId: UUID): Int = jdbcTemplate.queryForObject(
		"select count(*) from routine_executions where workspace_id = ? and routine_id = ?",
		Int::class.java,
		devContext.devWorkspaceId,
		routineId,
	) ?: 0

	private fun executionId(routineId: UUID, deliveryId: String): UUID = jdbcTemplate.queryForObject(
		"select execution.id from routine_executions execution join github_webhook_deliveries delivery on delivery.id = execution.trigger_delivery_id where execution.workspace_id = ? and execution.routine_id = ? and delivery.external_delivery_id = ?",
		UUID::class.java,
		devContext.devWorkspaceId,
		routineId,
		deliveryId,
	)!!

	private fun deliveryUuid(deliveryId: String): UUID = jdbcTemplate.queryForObject(
		"select id from github_webhook_deliveries where external_delivery_id = ?",
		UUID::class.java,
		deliveryId,
	)!!

	private fun executionStatus(executionId: UUID): RoutineExecutionStatus = assertNotNull(
		agentPersistence.findExecution(devContext.devWorkspaceId, executionId),
	).status

	private fun triggerKey(executionId: UUID): String = jdbcTemplate.queryForObject(
		"select trigger_key from routine_executions where id = ?",
		String::class.java,
		executionId,
	)!!

	private fun seedCount(executionId: UUID): Int = jdbcTemplate.queryForObject(
		"select count(*) from agent_run_inputs input join agent_runs run on run.workspace_id = input.workspace_id and run.id = input.agent_run_id where run.routine_execution_id = ? and input.input_kind = 'SEED'",
		Int::class.java,
		executionId,
	) ?: 0

	private fun seedIds(executionId: UUID): List<UUID> = jdbcTemplate.query(
		"select input.writing_block_id from agent_run_inputs input join agent_runs run on run.workspace_id = input.workspace_id and run.id = input.agent_run_id where run.routine_execution_id = ? and input.input_kind = 'SEED' order by input.order_index",
		{ rs, _ -> rs.getObject(1, UUID::class.java) },
		executionId,
	)

	private fun evidenceCount(routineId: UUID, deliveryId: String): Int = jdbcTemplate.queryForObject(
		"select count(*) from routine_execution_evidence evidence join routine_executions execution on execution.id = evidence.execution_id join github_webhook_deliveries delivery on delivery.id = execution.trigger_delivery_id where execution.routine_id = ? and delivery.external_delivery_id = ?",
		Int::class.java,
		routineId,
		deliveryId,
	) ?: 0

	private fun count(table: String): Int = jdbcTemplate.queryForObject(
		"select count(*) from $table where workspace_id = ?",
		Int::class.java,
		devContext.devWorkspaceId,
	) ?: 0

	private fun drainOne() {
		assertEquals(1, routineWorker.drain())
	}

	private fun randomPositiveLong(): Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun noOpRoutineRunDispatcher(
			@org.springframework.beans.factory.annotation.Qualifier("routineTaskExecutor") taskExecutor: org.springframework.core.task.TaskExecutor,
			@Lazy worker: RoutineWorker,
			agentProperties: RoutineAgentProperties,
			@org.springframework.beans.factory.annotation.Qualifier("routineRetryExecutor") retryExecutor: java.util.concurrent.ScheduledExecutorService,
		): RoutineRunDispatcher = object : RoutineRunDispatcher(taskExecutor, worker, agentProperties, retryExecutor, java.time.Clock.systemUTC()) {
			override fun dispatch() {}
			override fun scheduleDelayed(at: java.time.Instant) {}
		}

		@Bean
		@Primary
		fun noOpAgentRunDispatcher(
			@org.springframework.beans.factory.annotation.Qualifier("agentRunTaskExecutor") taskExecutor: org.springframework.core.task.TaskExecutor,
			@Lazy worker: AgentRunWorker,
			properties: RoutineAgentProperties,
			@org.springframework.beans.factory.annotation.Qualifier("agentRunRetryExecutor") retryExecutor: java.util.concurrent.ScheduledExecutorService,
		): AgentRunDispatcher = object : AgentRunDispatcher(taskExecutor, worker, properties, retryExecutor, java.time.Clock.systemUTC()) {
			override fun dispatch() {}
			override fun scheduleDelayed(at: java.time.Instant) {}
		}
	}
}

private data class RepositoryFixture(
	val connectionId: UUID,
	val namespaceId: UUID,
	val bindingId: UUID,
	val scopeId: UUID,
	val installationId: Long,
	val repositoryId: Long,
)
