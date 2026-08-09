package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.generation.GenerationRunDispatcher
import com.plot.api.generation.GenerationRunService
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class, RoutineWorkerIntegrationTest.DispatchConfig::class)
@TestPropertySource(properties = [
	"plot.routines.poll-delay=PT1H",
	"plot.routines.github-event-poll-delay=PT1H",
])
class RoutineWorkerIntegrationTest {
	@Autowired private lateinit var persistence: RoutinePersistence
	@Autowired private lateinit var worker: RoutineWorker
	@Autowired private lateinit var generationRunService: GenerationRunService
	@Autowired private lateinit var evidenceBudget: RoutineEvidenceBudget
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	private val sourceScopeIds = mutableListOf<UUID>()

	@BeforeEach
	fun bootstrap() {
		devBootstrapService.bootstrap()
	}

	@AfterEach
	fun removeRoutines() {
		sourceScopeIds.forEach { jdbcTemplate.update("delete from routines where source_scope_id = ?", it) }
		sourceScopeIds.clear()
	}

	@Test
	fun `compound activity cursor drains more than twenty blocks and sees changed upserts`() {
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

		runNow(routine.id)
		val firstRunId = assertNotNull(persistence.find(routine.workspaceId, routine.id)?.lastGenerationRunId)
		assertEquals(blockIds.take(20), generationInputIds(firstRunId))

		runNow(routine.id)
		val secondRunId = assertNotNull(persistence.find(routine.workspaceId, routine.id)?.lastGenerationRunId)
		assertEquals(blockIds.drop(20), generationInputIds(secondRunId))

		jdbcTemplate.update(
			"""
			update writing_blocks
			set title = 'changed activity', updated_at = ?,
			    activity_sequence = nextval('writing_block_activity_sequence')
			where workspace_id = ? and id = ?
			""".trimIndent(),
			Timestamp.from(activityAt.plusSeconds(60)),
			devContext.devWorkspaceId,
			blockIds.first(),
		)
		runNow(routine.id)
		val changedRunId = assertNotNull(persistence.find(routine.workspaceId, routine.id)?.lastGenerationRunId)
		assertEquals(listOf(blockIds.first()), generationInputIds(changedRunId))
	}

	@Test
	fun `legacy cursor position avoids replay and a later revision is visible`() {
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

		runNow(routine.id)
		val noReplay = assertNotNull(persistence.find(routine.workspaceId, routine.id))
		assertEquals("NO_ACTIVITY", noReplay.lastRunStatus)
		assertNull(noReplay.lastGenerationRunId)

		jdbcTemplate.update(
			"""
			update writing_blocks
			set title = 'new revision', activity_sequence = nextval('writing_block_activity_sequence'), updated_at = now()
			where workspace_id = ? and id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
			blockId,
		)
		runNow(routine.id)
		val changedRunId = assertNotNull(persistence.find(routine.workspaceId, routine.id)?.lastGenerationRunId)
		assertEquals(listOf(blockId), generationInputIds(changedRunId))
	}

	@Test
	fun `oversized evidence is failed and consumed alone so later evidence is not wedged`() {
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

		runNow(routine.id)
		val skipped = assertNotNull(persistence.find(routine.workspaceId, routine.id))
		assertEquals("FAILED", skipped.lastRunStatus)
		assertEquals("ROUTINE_EVIDENCE_TOO_LARGE", skipped.lastErrorCode)
		assertNull(skipped.lastGenerationRunId)
		assertEquals(activitySequence(oversizedId), skipped.activityCursorSequence)

		runNow(routine.id)
		val laterRunId = assertNotNull(persistence.find(routine.workspaceId, routine.id)?.lastGenerationRunId)
		assertEquals(listOf(laterId), generationInputIds(laterRunId))
	}

	@Test
	fun `stale reclaim keeps one execution identity and a failed attempt clears the old generation projection`() {
		val fixture = insertSourceScope()
		val routine = persistence.insert(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			name = "Recoverable routine",
			sourceScopeId = fixture.scopeId,
			instruction = "Draft activity",
			cadence = RoutineCadence.DAILY,
		)
		val blockId = insertBlock(fixture, 1, Instant.parse("2026-08-09T00:00:00Z"))
		val queued = assertNotNull(persistence.queueNow(routine.workspaceId, routine.id))
		val firstClaimAt = Instant.now()
		val firstClaim = assertNotNull(persistence.claimById(
			"first-worker",
			routine.workspaceId,
			routine.id,
			firstClaimAt,
			firstClaimAt.minus(Duration.ofMinutes(2)),
		))
		val executionId = assertNotNull(firstClaim.activeExecutionId)
		assertEquals(queued.activeExecutionId, executionId)
		val principal = WorkspacePrincipal(routine.workspaceId, routine.createdByUserId)
		val firstGeneration = generationRunService.createForPrincipal(
			principal,
			routine.sourceScopeId,
			listOf(blockId),
			routine.instruction,
			"routine:${routine.id}:$executionId",
		)

		jdbcTemplate.update(
			"update routines set claimed_at = ? where id = ?",
			Timestamp.from(firstClaimAt.minus(Duration.ofMinutes(10))),
			routine.id,
		)
		val reclaimAt = firstClaimAt.plus(Duration.ofMinutes(1))
		val reclaimed = assertNotNull(persistence.claimById(
			"replacement-worker",
			routine.workspaceId,
			routine.id,
			reclaimAt,
			reclaimAt.minus(Duration.ofMinutes(2)),
		))
		assertEquals(executionId, reclaimed.activeExecutionId)
		val replayedGeneration = generationRunService.createForPrincipal(
			principal,
			routine.sourceScopeId,
			listOf(blockId),
			routine.instruction,
			"routine:${routine.id}:$executionId",
		)
		assertEquals(firstGeneration.runId, replayedGeneration.runId)

		persistence.finish(
			claim = reclaimed,
			now = reclaimAt,
			nextRunAt = RoutineCadence.DAILY.nextAfter(reclaimAt),
			status = "FAILED",
			errorCode = "ROUTINE_RUN_FAILED",
		)
		val failed = assertNotNull(persistence.find(routine.workspaceId, routine.id))
		assertEquals("FAILED", failed.lastRunStatus)
		assertEquals("ROUTINE_RUN_FAILED", failed.lastErrorCode)
		assertNull(failed.lastGenerationRunId)
	}

	private fun runNow(routineId: UUID) {
		val queued = assertNotNull(persistence.queueNow(devContext.devWorkspaceId, routineId))
		worker.runNow(queued.workspaceId, queued.id)
	}

	private fun generationInputIds(runId: UUID): List<UUID> = jdbcTemplate.query(
		"select writing_block_id from generation_inputs where generation_run_id = ? order by order_index",
		{ rs, _ -> rs.getObject(1, UUID::class.java) },
		runId,
	)

	private fun insertSourceScope(): SourceFixture {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
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
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, external_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/plot', 'acme/plot', 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			"repository-${UUID.randomUUID()}",
		)
		sourceScopeIds += scopeId
		return SourceFixture(namespaceId, scopeId)
	}

	private fun insertBlock(
		fixture: SourceFixture,
		index: Int,
		updatedAt: Instant,
		title: String = "Activity $index",
	): UUID {
		val blockId = UUID.randomUUID()
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

	private fun activitySequence(blockId: UUID): Long = jdbcTemplate.queryForObject(
		"select activity_sequence from writing_blocks where id = ?",
		Long::class.java,
		blockId,
	)!!

	@TestConfiguration(proxyBeanMethods = false)
	class DispatchConfig {
		@Bean
		@Primary
		fun inertGenerationRunDispatcher(): GenerationRunDispatcher =
			GenerationRunDispatcher(TaskExecutor { }) { false }
	}
}

private data class SourceFixture(
	val namespaceId: UUID,
	val scopeId: UUID,
)
