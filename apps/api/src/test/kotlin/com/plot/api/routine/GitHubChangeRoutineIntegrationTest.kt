package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.generation.GenerationRunDispatcher
import com.plot.api.github.GitHubWebhookCommit
import com.plot.api.github.GitHubWebhookService
import com.plot.api.github.ParsedGitHubWebhook
import java.time.Instant
import java.time.Duration
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
@Import(TestcontainersConfiguration::class, GitHubChangeRoutineIntegrationTest.DispatchConfig::class)
@TestPropertySource(properties = [
	"plot.routines.poll-delay=PT1H",
	"plot.routines.github-event-poll-delay=PT1H",
])
class GitHubChangeRoutineIntegrationTest {
	@Autowired private lateinit var webhookService: GitHubWebhookService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var eventWorker: GitHubRoutineEventWorker
	@Autowired private lateinit var eventPersistence: GitHubRoutineEventPersistence
	private val routineIds = mutableListOf<UUID>()
	private var installationId = 0L
	private var repositoryId = 0L

	@BeforeEach
	fun bootstrap() {
		devBootstrapService.bootstrap()
		installationId = randomPositiveLong()
		repositoryId = randomPositiveLong()
	}

	@AfterEach
	fun removeRoutines() {
		routineIds.forEach { jdbcTemplate.update("delete from routines where id = ?", it) }
		routineIds.clear()
	}

	@Test
	fun `default branch push queues enabled github change routines once per delivery`() {
		val sourceScopeId = bindRepository()
		val routineId = insertRoutine(sourceScopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val sha = "b".repeat(40)
		val webhook = ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "push",
			eventAction = null,
			installationId = installationId,
			repositoryId = repositoryId,
			ref = "refs/heads/main",
			beforeSha = "a".repeat(40),
			afterSha = sha,
			tagName = null,
			refCreated = false,
			refDeleted = false,
			forced = false,
			commits = listOf(GitHubWebhookCommit(
				sha = sha,
				message = "Ship routines\n\nGenerate a draft on push.",
				author = "octocat",
				timestamp = Instant.parse("2026-08-09T00:00:00Z"),
				url = "https://github.com/acme/repo/commit/$sha",
			)),
			payloadHash = "c".repeat(64),
		)

		webhookService.accept(webhook)
		webhookService.accept(webhook)
		assertEquals("QUEUED", eventStatus(routineId, deliveryId))
		assertEquals(1, eventWorker.drain())

		assertEquals("QUEUED", jdbcTemplate.queryForObject(
			"select disposition from github_webhook_deliveries where external_delivery_id = ?",
			String::class.java,
			deliveryId,
		))
		assertEventGeneration(routineId, deliveryId)
		assertEquals("SUCCEEDED", eventStatus(routineId, deliveryId))
		assertEquals("QUEUED", jdbcTemplate.queryForObject(
			"select last_run_status from routines where id = ?", String::class.java, routineId,
		))
		assertEquals("Ship routines", jdbcTemplate.queryForObject(
			"select title from writing_blocks where workspace_id = ? and external_object_key = ?",
			String::class.java,
			devContext.devWorkspaceId,
			"commit:$sha",
		))
	}

	@Test
	fun `published release queues only release routines`() {
		val sourceScopeId = bindRepository()
		val routineId = insertRoutine(sourceScopeId, RoutineCadence.ON_GITHUB_RELEASE)
		val deliveryId = "delivery-${UUID.randomUUID()}"

		webhookService.accept(ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "release",
			eventAction = "published",
			installationId = installationId,
			repositoryId = repositoryId,
			ref = null,
			beforeSha = null,
			afterSha = null,
			tagName = "v2.0.0",
			refCreated = null,
			refDeleted = null,
			forced = null,
			payloadHash = "d".repeat(64),
		))
		assertEquals(1, eventWorker.drain())

		assertEventRun(routineId, deliveryId, "release", "Release v2.0.0 published")
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from github_release_draft_requests where workspace_id = ? and source_scope_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			sourceScopeId,
		))
	}

	@Test
	fun `tag push queues only tag routines`() {
		val sourceScopeId = bindRepository()
		val routineId = insertRoutine(sourceScopeId, RoutineCadence.ON_GIT_TAG)
		val deliveryId = "delivery-${UUID.randomUUID()}"

		webhookService.accept(ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "push",
			eventAction = null,
			installationId = installationId,
			repositoryId = repositoryId,
			ref = "refs/tags/v2.0.0",
			beforeSha = "0".repeat(40),
			afterSha = "e".repeat(40),
			tagName = "v2.0.0",
			refCreated = true,
			refDeleted = false,
			forced = false,
			payloadHash = "f".repeat(64),
		))
		assertEquals(1, eventWorker.drain())

		assertEventRun(routineId, deliveryId, "tag", "Tag v2.0.0 pushed")
	}

	@Test
	fun `tag evidence reserves one central budget slot for the event block`() {
		val sourceScopeId = bindRepository()
		val routineId = insertRoutine(sourceScopeId, RoutineCadence.ON_GIT_TAG)
		val deliveryId = "delivery-${UUID.randomUUID()}"
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
			installationId = installationId,
			repositoryId = repositoryId,
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
		assertEquals(1, jdbcTemplate.queryForObject(
			"""
			select count(*)
			from routine_github_event_evidence evidence
			join routine_github_event_runs event_run on event_run.id = evidence.event_run_id
			join github_webhook_deliveries delivery on delivery.id = event_run.delivery_id
			join writing_blocks block on block.id = evidence.writing_block_id
			where event_run.routine_id = ? and delivery.external_delivery_id = ? and block.source_kind = 'tag'
			""".trimIndent(),
			Int::class.java,
			routineId,
			deliveryId,
		))
	}

	@Test
	fun `one disabled routine fails independently while another delivery job succeeds`() {
		val sourceScopeId = bindRepository()
		insertRoutine(sourceScopeId, RoutineCadence.ON_GITHUB_CHANGE)
		insertRoutine(sourceScopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val sha = "9".repeat(40)
		webhookService.accept(ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "push",
			eventAction = null,
			installationId = installationId,
			repositoryId = repositoryId,
			ref = "refs/heads/main",
			beforeSha = "8".repeat(40),
			afterSha = sha,
			tagName = null,
			refCreated = false,
			refDeleted = false,
			forced = false,
			commits = listOf(GitHubWebhookCommit(
				sha = sha,
				message = "Independent routine jobs",
				author = "octocat",
				timestamp = Instant.parse("2026-08-09T00:00:00Z"),
				url = "https://github.com/acme/repo/commit/$sha",
			)),
			payloadHash = "2".repeat(64),
		))
		val firstRoutineId = jdbcTemplate.queryForObject(
			"""
			select event_run.routine_id
			from routine_github_event_runs event_run
			join github_webhook_deliveries delivery on delivery.id = event_run.delivery_id
			where delivery.external_delivery_id = ?
			order by event_run.id limit 1
			""".trimIndent(),
			UUID::class.java,
			deliveryId,
		)
		jdbcTemplate.update("update routines set enabled = false where id = ?", firstRoutineId)

		assertEquals(1, eventWorker.drain())
		assertEquals(1, eventWorker.drain())

		assertEquals(1, jdbcTemplate.queryForObject(
			"""
			select count(*) from routine_github_event_runs event_run
			join github_webhook_deliveries delivery on delivery.id = event_run.delivery_id
			where delivery.external_delivery_id = ? and event_run.status = 'FAILED'
			""".trimIndent(),
			Int::class.java,
			deliveryId,
		))
		assertEquals(1, jdbcTemplate.queryForObject(
			"""
			select count(*) from routine_github_event_runs event_run
			join github_webhook_deliveries delivery on delivery.id = event_run.delivery_id
			where delivery.external_delivery_id = ? and event_run.status = 'SUCCEEDED'
			""".trimIndent(),
			Int::class.java,
			deliveryId,
		))
		assertEquals(1, jdbcTemplate.queryForObject(
			"""
			select count(*) from generation_runs run
			where run.idempotency_key in (
			  select 'routine-github:' || event_run.id::text
			  from routine_github_event_runs event_run
			  join github_webhook_deliveries delivery on delivery.id = event_run.delivery_id
			  where delivery.external_delivery_id = ?
			)
			""".trimIndent(),
			Int::class.java,
			deliveryId,
		))
		assertEquals("QUEUED", jdbcTemplate.queryForObject(
			"select disposition from github_webhook_deliveries where external_delivery_id = ?",
			String::class.java,
			deliveryId,
		))
	}

	@Test
	fun `stale event claim is reclaimed after a worker restart with one generation`() {
		val sourceScopeId = bindRepository()
		val routineId = insertRoutine(sourceScopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val deliveryId = "delivery-${UUID.randomUUID()}"
		val sha = "7".repeat(40)
		webhookService.accept(ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "push",
			eventAction = null,
			installationId = installationId,
			repositoryId = repositoryId,
			ref = "refs/heads/main",
			beforeSha = "6".repeat(40),
			afterSha = sha,
			tagName = null,
			refCreated = false,
			refDeleted = false,
			forced = false,
			commits = listOf(GitHubWebhookCommit(
				sha = sha,
				message = "Recover after restart",
				author = "octocat",
				timestamp = Instant.parse("2026-08-09T00:00:00Z"),
				url = "https://github.com/acme/repo/commit/$sha",
			)),
			payloadHash = "3".repeat(64),
		))
		val claimed = assertNotNull(eventPersistence.claimNext("dead-worker", Instant.now(), Duration.ofMinutes(2)))
		jdbcTemplate.update(
			"update routine_github_event_runs set claimed_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
			claimed.id,
		)

		assertEquals(1, eventWorker.drain())

		val recovered = assertNotNull(eventPersistence.find(claimed.id))
		assertEquals(GitHubRoutineEventStatus.SUCCEEDED, recovered.status)
		assertEquals(2, recovered.attemptCount)
		assertEventGeneration(routineId, deliveryId)
	}

	@Test
	fun `queued event fails explicitly when its evidence changes before generation`() {
		val sourceScopeId = bindRepository()
		val routineId = insertRoutine(sourceScopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val deliveryId = "delivery-${UUID.randomUUID()}"
		acceptPush(deliveryId, "4".repeat(40), "Original evidence")
		val eventRunId = eventRunId(routineId, deliveryId)
		val queued = assertNotNull(eventPersistence.find(eventRunId))
		val evidence = queued.evidence.single()
		jdbcTemplate.update(
			"""
			update writing_blocks
			set title = 'Changed evidence', activity_sequence = nextval('writing_block_activity_sequence'), updated_at = now()
			where workspace_id = ? and id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
			evidence.writingBlockId,
		)

		assertEquals(1, eventWorker.drain())

		val failed = assertNotNull(eventPersistence.find(eventRunId))
		assertEquals(GitHubRoutineEventStatus.FAILED, failed.status)
		assertEquals("ROUTINE_EVIDENCE_CHANGED", failed.errorCode)
		assertNull(failed.generationRunId)
		assertNull(jdbcTemplate.queryForObject(
			"select claimed_by from routines where id = ?",
			String::class.java,
			routineId,
		))
	}

	@Test
	fun `older event claim and finish preserve a newer queued projection`() {
		val sourceScopeId = bindRepository()
		val routineId = insertRoutine(sourceScopeId, RoutineCadence.ON_GITHUB_CHANGE)
		val firstDeliveryId = "delivery-${UUID.randomUUID()}"
		val secondDeliveryId = "delivery-${UUID.randomUUID()}"
		acceptPush(firstDeliveryId, "5".repeat(40), "First event")
		acceptPush(secondDeliveryId, "6".repeat(40), "Second event")
		val firstEventRunId = eventRunId(routineId, firstDeliveryId)
		val secondEventRunId = eventRunId(routineId, secondDeliveryId)
		assertEquals(secondEventRunId, projectedExecutionId(routineId))

		val claimed = assertNotNull(eventPersistence.claimNext("projection-worker", Instant.now(), Duration.ofMinutes(2)))
		assertEquals(firstEventRunId, claimed.id)
		assertEquals(secondEventRunId, projectedExecutionId(routineId))

		eventPersistence.fail(claimed, "TEST_FAILURE", Instant.now())
		assertEquals(secondEventRunId, projectedExecutionId(routineId))
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

	private fun assertEventRun(routineId: UUID, deliveryId: String, sourceKind: String, title: String) {
		assertEventGeneration(routineId, deliveryId)
		assertEquals(title, jdbcTemplate.queryForObject(
			"""
			select block.title
			from routine_github_event_runs event_run
			join github_webhook_deliveries delivery on delivery.id = event_run.delivery_id
			join routine_github_event_evidence evidence on evidence.event_run_id = event_run.id
			join writing_blocks block on block.id = evidence.writing_block_id
			where event_run.routine_id = ? and delivery.external_delivery_id = ? and block.source_kind = ?
			""".trimIndent(),
			String::class.java,
			routineId,
			deliveryId,
			sourceKind,
		))
	}

	private fun assertEventGeneration(routineId: UUID, deliveryId: String) {
		val eventRunId = eventRunId(routineId, deliveryId)
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from generation_runs where workspace_id = ? and idempotency_key = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			"routine-github:$eventRunId",
		))
	}

	private fun eventRunId(routineId: UUID, deliveryId: String): UUID = jdbcTemplate.queryForObject(
			"""
			select event_run.id
			from routine_github_event_runs event_run
			join github_webhook_deliveries delivery on delivery.id = event_run.delivery_id
			where event_run.routine_id = ? and delivery.external_delivery_id = ?
			""".trimIndent(),
			UUID::class.java,
			routineId,
			deliveryId,
		)!!

	private fun projectedExecutionId(routineId: UUID): UUID? = jdbcTemplate.queryForObject(
		"select last_execution_id from routines where id = ?",
		UUID::class.java,
		routineId,
	)

	private fun eventStatus(routineId: UUID, deliveryId: String): String = jdbcTemplate.queryForObject(
		"""
		select event_run.status
		from routine_github_event_runs event_run
		join github_webhook_deliveries delivery on delivery.id = event_run.delivery_id
		where event_run.routine_id = ? and delivery.external_delivery_id = ?
		""".trimIndent(),
		String::class.java,
		routineId,
		deliveryId,
	)!!

	private fun evidenceCount(routineId: UUID, deliveryId: String): Int = jdbcTemplate.queryForObject(
		"""
		select count(*)
		from routine_github_event_evidence evidence
		join routine_github_event_runs event_run on event_run.id = evidence.event_run_id
		join github_webhook_deliveries delivery on delivery.id = event_run.delivery_id
		where event_run.routine_id = ? and delivery.external_delivery_id = ?
		""".trimIndent(),
		Int::class.java,
		routineId,
		deliveryId,
	)!!

	private fun bindRepository(): UUID {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at)
				values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())""".trimIndent(),
			connectionId, devContext.devWorkspaceId, installationId.toString(), devContext.devUserId,
		)
		jdbcTemplate.update(
			"""insert into source_namespaces (id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
				values (?, ?, 'GITHUB', 'REPOSITORY', ?, 'ACTIVE', now(), now())""".trimIndent(),
			namespaceId, devContext.devWorkspaceId, "repository:$repositoryId",
		)
		jdbcTemplate.update(
			"""insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at)
				values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())""".trimIndent(),
			bindingId, devContext.devWorkspaceId, connectionId, namespaceId,
		)
		jdbcTemplate.update(
			"""insert into source_scopes (id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind, external_scope_key, external_key, display_name, metadata, status, created_at, updated_at)
				values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, 'acme/repo', '{"defaultBranch":"main"}'::jsonb, 'ACTIVE', now(), now())""".trimIndent(),
			scopeId, devContext.devWorkspaceId, namespaceId, repositoryId.toString(), "acme/repo-$repositoryId",
		)
		return scopeId
	}

	private fun acceptPush(deliveryId: String, sha: String, message: String) {
		webhookService.accept(ParsedGitHubWebhook(
			externalDeliveryId = deliveryId,
			eventType = "push",
			eventAction = null,
			installationId = installationId,
			repositoryId = repositoryId,
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
				timestamp = Instant.now(),
				url = "https://github.com/acme/repo/commit/$sha",
			)),
			payloadHash = UUID.randomUUID().toString().replace("-", "").padEnd(64, '0'),
		))
	}

	private fun randomPositiveLong(): Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE

	@TestConfiguration(proxyBeanMethods = false)
	class DispatchConfig {
		@Bean
		@Primary
		fun inertGenerationRunDispatcher(): GenerationRunDispatcher =
			GenerationRunDispatcher(TaskExecutor { }) { false }
	}
}
