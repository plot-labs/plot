package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.github.GitHubClient
import com.plot.api.github.GitHubPullRequest
import com.plot.api.github.GitHubRepository
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class, RoutineWorkerIntegrationTest.Config::class)
@TestPropertySource(properties = [
	"plot.routines.poll-delay=PT1H",
	"plot.routines.github-event-poll-delay=PT1H",
	"plot.routine-agent.workers-enabled=true",
])
class RoutineWorkerIntegrationTest {
	@Autowired private lateinit var persistence: RoutinePersistence
	@Autowired private lateinit var agentPersistence: RoutineAgentPersistence
	@Autowired private lateinit var worker: RoutineWorker
	@Autowired private lateinit var evidenceBudget: RoutineEvidenceBudget
	@Autowired private lateinit var agentProperties: RoutineAgentProperties
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	private val sourceScopeIds = mutableListOf<UUID>()
	private val sourceNamespaceIds = mutableListOf<UUID>()
	private val bindingIds = mutableListOf<UUID>()
	private val connectionIds = mutableListOf<UUID>()
	private val writingBlockIds = mutableListOf<UUID>()

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
		jdbcTemplate.update("delete from routines where workspace_id = ?", workspaceId)
		writingBlockIds.forEach { blockId ->
			jdbcTemplate.update("delete from writing_block_scopes where writing_block_id = ?", blockId)
			jdbcTemplate.update("delete from writing_blocks where id = ?", blockId)
		}
		jdbcTemplate.update(
			"delete from source_observations where workspace_id = ? and authority_owner like 'github:routine-refresh:%'",
			workspaceId,
		)
		sourceScopeIds.forEach { sourceScopeId ->
			jdbcTemplate.update("delete from source_scopes where id = ?", sourceScopeId)
		}
		bindingIds.forEach { bindingId ->
			jdbcTemplate.update("delete from connection_namespace_bindings where id = ?", bindingId)
		}
		sourceNamespaceIds.forEach { namespaceId ->
			jdbcTemplate.update("delete from source_namespaces where id = ?", namespaceId)
		}
		connectionIds.forEach { connectionId ->
			jdbcTemplate.update("delete from connections where id = ?", connectionId)
		}
		sourceScopeIds.clear()
		sourceNamespaceIds.clear()
		bindingIds.clear()
		connectionIds.clear()
		writingBlockIds.clear()
	}

	@Test
	fun `activity cursor drains more than twenty blocks and changed upserts once`() {
		val fixture = insertSourceScope()
		val routine = persistence.insert(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			name = "Backlog routine",
			sourceScopeId = fixture.scopeId,
			instruction = "Draft all activity",
			cadence = RoutineCadence.WEEKLY,
		)
		val activityAt = Instant.parse("2026-08-09T00:00:00Z")
		val blockIds = (1..25).map { index -> insertBlock(fixture, index, activityAt) }

		val firstExecutionId = runNow(routine.id, "backlog-first")
		assertEquals(blockIds.take(20), seedInputIds(firstExecutionId))
		assertEquals(RoutineExecutionStatus.DISPATCHED, executionStatus(firstExecutionId))

		val secondExecutionId = runNow(routine.id, "backlog-second")
		assertEquals(blockIds.drop(20), seedInputIds(secondExecutionId))
		assertEquals(RoutineExecutionStatus.DISPATCHED, executionStatus(secondExecutionId))

		jdbcTemplate.update(
			"""
			update writing_blocks
			set title = 'changed activity', updated_at = ?
			where workspace_id = ? and id = ?
			""".trimIndent(),
			Timestamp.from(activityAt.plusSeconds(60)),
			devContext.devWorkspaceId,
			blockIds.first(),
		)

		val changedExecutionId = runNow(routine.id, "backlog-changed")
		assertEquals(listOf(blockIds.first()), seedInputIds(changedExecutionId))
		assertEquals(3, count("routine_executions"))
		assertEquals(3, count("work_sessions"))
		assertEquals(3, count("agent_runs"))
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from generation_runs where workspace_id = ? and agent_run_id is not null",
			Int::class.java,
			devContext.devWorkspaceId,
		))
	}

	@Test
	fun `legacy cursor position avoids replay and later revision is visible`() {
		val fixture = insertSourceScope()
		val activityAt = Instant.parse("2026-08-09T00:00:00Z")
		val blockId = insertBlock(fixture, 1, activityAt)
		val routine = persistence.insert(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			name = "Upgraded routine",
			sourceScopeId = fixture.scopeId,
			instruction = "Draft only new activity",
			cadence = RoutineCadence.DAILY,
		)
		jdbcTemplate.update(
			"""
			update routines
			set last_run_at = ?, activity_cursor_sequence = (
			  select max(activity_sequence) from writing_blocks where workspace_id = ? and id = ?
			)
			where id = ?
			""".trimIndent(),
			Timestamp.from(activityAt.plusSeconds(1)),
			devContext.devWorkspaceId,
			blockId,
			routine.id,
		)

		val noReplayExecutionId = runNow(routine.id, "legacy-no-replay")
		assertEquals(RoutineExecutionStatus.NO_ACTIVITY, executionStatus(noReplayExecutionId))
		assertEquals(0, seedInputIds(noReplayExecutionId).size)
		assertEquals(0, count("work_sessions"))

		jdbcTemplate.update(
			"update writing_blocks set title = 'new revision', updated_at = now() where workspace_id = ? and id = ?",
			devContext.devWorkspaceId,
			blockId,
		)
		val changedExecutionId = runNow(routine.id, "legacy-revision")
		assertEquals(listOf(blockId), seedInputIds(changedExecutionId))
		assertEquals(RoutineExecutionStatus.DISPATCHED, executionStatus(changedExecutionId))
	}

	@Test
	fun `reactivated scope membership is visible after the routine cursor`() {
		val fixture = insertSourceScope()
		val routine = persistence.insert(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			name = "Membership routine",
			sourceScopeId = fixture.scopeId,
			instruction = "Draft visible activity",
			cadence = RoutineCadence.DAILY,
		)
		val blockId = insertBlock(fixture, 1, Instant.parse("2026-08-09T00:00:00Z"))
		runNow(routine.id, "membership-first")

		jdbcTemplate.update(
			"update writing_block_scopes set status = 'TOMBSTONED' where writing_block_id = ? and source_scope_id = ?",
			blockId,
			fixture.scopeId,
		)
		jdbcTemplate.update(
			"update writing_block_scopes set status = 'ACTIVE' where writing_block_id = ? and source_scope_id = ?",
			blockId,
			fixture.scopeId,
		)

		val reactivatedExecutionId = runNow(routine.id, "membership-reactivated")
		assertEquals(listOf(blockId), seedInputIds(reactivatedExecutionId))
	}

	@Test
	fun `oversized evidence is bounded and consumed as activity`() {
		val fixture = insertSourceScope()
		val routine = persistence.insert(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			name = "Bounded routine",
			sourceScopeId = fixture.scopeId,
			instruction = "Draft bounded activity",
			cadence = RoutineCadence.DAILY,
		)
		val at = Instant.parse("2026-08-09T00:00:00Z")
		val oversizedId = insertBlock(fixture, 1, at, title = "x".repeat(evidenceBudget.maxCharacters + 1))
		val laterId = insertBlock(fixture, 2, at.plusSeconds(1))

		val firstExecutionId = runNow(routine.id, "oversized-first")
		assertEquals(RoutineExecutionStatus.DISPATCHED, executionStatus(firstExecutionId))
		assertEquals(listOf(oversizedId), seedInputIds(firstExecutionId))
		assertEquals(1, count("work_sessions"))
		assertEquals(1, count("agent_runs"))
		val boundedCharacters = assertNotNull(jdbcTemplate.queryForObject(
			"select length(coalesce(snapshot_title, '')) + length(snapshot_body) from agent_run_inputs where agent_run_id = (select id from agent_runs where routine_execution_id = ?)",
			Int::class.java,
			firstExecutionId,
		))
		assertEquals(agentProperties.maxInputCharacters, boundedCharacters)

		val secondExecutionId = runNow(routine.id, "oversized-second")
		assertEquals(listOf(laterId), seedInputIds(secondExecutionId))
	}

	@Test
	fun `stale reclaim keeps one execution identity and fences the old owner`() {
		val fixture = insertSourceScope()
		val routine = persistence.insert(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			name = "Recoverable routine",
			sourceScopeId = fixture.scopeId,
			instruction = "Draft activity",
			cadence = RoutineCadence.DAILY,
		)
		val execution = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = routine.workspaceId,
				routineId = routine.id,
				createdByUserId = routine.createdByUserId,
				triggerSourceScopeId = routine.sourceScopeId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "manual:${routine.id}:stale",
				requestFingerprint = "stale-fingerprint",
				activityCursorBefore = routine.activityCursorSequence,
			),
		)
		val firstClaimAt = Instant.parse("2026-08-09T00:00:00Z")
		assertNotNull(agentPersistence.claimById(
			"first-worker",
			routine.workspaceId,
			execution.id,
			firstClaimAt,
			firstClaimAt.minus(Duration.ofMinutes(2)),
		))
		val recovered = assertNotNull(agentPersistence.claimById(
			"replacement-worker",
			routine.workspaceId,
			execution.id,
			firstClaimAt.plus(Duration.ofMinutes(3)),
			firstClaimAt.plus(Duration.ofMinutes(1)),
		))
		assertEquals(execution.id, recovered.id)
		assertEquals("replacement-worker", recovered.claimedBy)
		val staleFailure = runCatching {
			agentPersistence.markNoActivity(
				routine.workspaceId,
				execution.id,
				firstClaimAt.plus(Duration.ofMinutes(3)),
				workerId = "first-worker",
			)
		}.exceptionOrNull()
		assertNotNull(staleFailure)
		assertTrue(generateSequence(staleFailure) { it.cause }.any { it is RoutineExecutionStateException })
		agentPersistence.markNoActivity(
			routine.workspaceId,
			execution.id,
			firstClaimAt.plus(Duration.ofMinutes(3)),
			workerId = "replacement-worker",
		)
		assertEquals(RoutineExecutionStatus.NO_ACTIVITY, executionStatus(execution.id))
	}

	@Test
	fun `scheduled next slot derives from the persisted due slot`() {
		val fixture = insertSourceScope()
		val routine = persistence.insert(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			name = "Stable schedule",
			sourceScopeId = fixture.scopeId,
			instruction = "Check on the persisted cadence",
			cadence = RoutineCadence.DAILY,
		)
		val dueAt = Instant.parse("2026-08-01T09:00:00Z")
		jdbcTemplate.update(
			"update routines set next_run_at = ? where workspace_id = ? and id = ?",
			Timestamp.from(dueAt),
			devContext.devWorkspaceId,
			routine.id,
		)

		assertTrue(worker.drain())

		assertEquals(Timestamp.from(dueAt), jdbcTemplate.queryForObject(
			"select scheduled_for from routine_executions where routine_id = ? and trigger_kind = 'SCHEDULED'",
			Timestamp::class.java,
			routine.id,
		))
		assertEquals(Timestamp.from(dueAt.plus(Duration.ofDays(1))), jdbcTemplate.queryForObject(
			"select next_run_at from routines where id = ?",
			Timestamp::class.java,
			routine.id,
		))
	}

	private fun runNow(routineId: UUID, key: String): UUID {
		val routine = assertNotNull(persistence.find(devContext.devWorkspaceId, routineId))
		val execution = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = routine.workspaceId,
				routineId = routine.id,
				createdByUserId = routine.createdByUserId,
				triggerSourceScopeId = routine.sourceScopeId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "manual:${routine.id}:$key",
				requestFingerprint = "worker-test:${routine.id}|$key",
				activityCursorBefore = routine.activityCursorSequence,
			),
		)
		worker.runNow(routine.workspaceId, routine.id, execution.id)
		return execution.id
	}

	private fun executionStatus(executionId: UUID): RoutineExecutionStatus = assertNotNull(
		agentPersistence.findExecution(devContext.devWorkspaceId, executionId),
	).status

	private fun seedInputIds(executionId: UUID): List<UUID> = jdbcTemplate.query(
		"select input.writing_block_id from agent_run_inputs input join agent_runs run on run.workspace_id = input.workspace_id and run.id = input.agent_run_id where run.routine_execution_id = ? and input.input_kind = 'SEED' order by input.order_index",
		{ rs, _ -> rs.getObject(1, UUID::class.java) },
		executionId,
	)

	private fun count(table: String): Int = jdbcTemplate.queryForObject(
		"select count(*) from $table where workspace_id = ?",
		Int::class.java,
		devContext.devWorkspaceId,
	) ?: 0

	private fun insertSourceScope(): SourceFixture {
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
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'Acme', 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			devContext.devWorkspaceId,
			"installation-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at) values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())",
			bindingId,
			devContext.devWorkspaceId,
			connectionId,
			namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, external_key, display_name, status, status_changed_at, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/plot', 'acme/plot', 'ACTIVE', now(), now(), now())
			""".trimIndent(),
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			repositoryId.toString(),
		)
		sourceScopeIds += scopeId
		sourceNamespaceIds += namespaceId
		bindingIds += bindingId
		connectionIds += connectionId
		return SourceFixture(namespaceId, scopeId)
	}

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun routineWorkerGitHubClient(): GitHubClient = object : GitHubClient {
			override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> = error("not used")

			override fun listClosedPullRequests(
				installationId: Long,
				repositoryId: Long,
				owner: String,
				repository: String,
				pageCap: Int,
			): List<GitHubPullRequest> = emptyList()
		}
	}

	private fun insertBlock(
		fixture: SourceFixture,
		index: Int,
		updatedAt: Instant,
		title: String = "Activity $index",
	): UUID {
		val blockId = UUID.randomUUID()
		writingBlockIds += blockId
		val url = "https://github.com/acme/plot/commit/$blockId"
		jdbcTemplate.update(
			"""
			insert into writing_blocks (
			 id, workspace_id, source_namespace_id, external_object_key, source_origin, source_kind,
			 title, body, url, canonical_url, platform, content_hash, source_created_at, source_updated_at,
			 ingested_at, status, created_at, updated_at
			) values (?, ?, ?, ?, 'integration', 'commit', ?, null, ?, ?, 'github', ?, ?, ?, ?, 'ACTIVE', ?, ?)
			""".trimIndent(),
			blockId,
			devContext.devWorkspaceId,
			fixture.namespaceId,
			"commit:$blockId",
			title,
			url,
			url,
			"hash-$index",
			Timestamp.from(updatedAt),
			Timestamp.from(updatedAt),
			Timestamp.from(updatedAt),
			Timestamp.from(updatedAt),
			Timestamp.from(updatedAt),
		)
		jdbcTemplate.update(
			"""
			insert into writing_block_scopes (
			 id, workspace_id, source_namespace_id, writing_block_id, source_scope_id,
			 membership_kind, status, first_seen_at, last_seen_at
			) values (?, ?, ?, ?, ?, 'CONTAINED_IN', 'ACTIVE', ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			fixture.namespaceId,
			blockId,
			fixture.scopeId,
			Timestamp.from(updatedAt),
			Timestamp.from(updatedAt),
		)
		return blockId
	}
}

private data class SourceFixture(
	val namespaceId: UUID,
	val scopeId: UUID,
)
