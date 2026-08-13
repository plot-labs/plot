package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.ai.provider.AgentDecision
import com.plot.api.ai.provider.AgentDecisionAction
import com.plot.api.ai.provider.GenerationModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelCallResult
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.generation.GenerationPersistence
import com.plot.api.generation.GenerationRunDispatcher
import com.plot.api.generation.GenerationRunWorker
import com.plot.api.generation.GenerationWorkflowService
import com.plot.api.generation.model.ReviewVerdict
import com.plot.api.generation.model.ReviewerOutput
import com.plot.api.generation.model.SentenceReview
import com.plot.api.generation.model.TargetedRewriteOutput
import com.plot.api.generation.model.WriterOutput
import com.plot.api.generation.model.WriterSentence
import com.plot.api.routine.AgentRunWorker
import com.plot.api.routine.ScriptedAgentDecisionGateway
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.core.task.TaskExecutor
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(
	TestcontainersConfiguration::class,
	GitHubReleaseAutomationIntegrationTest.Config::class,
)
@ActiveProfiles("test")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.github.enabled=true",
	"plot.github.app-id=1",
	"plot.github.app-slug=plot",
	"plot.github.private-key=test-key",
	"plot.github.state-secret=test-state-secret",
	"plot.github.release-automation-enabled=true",
	"plot.routine-agent.workers-enabled=true",
	"plot.github.release-worker-poll-delay=PT24H",
	"server.address=127.0.0.1",
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GitHubReleaseAutomationIntegrationTest {
	@Autowired private lateinit var webhookService: GitHubWebhookService
	@Autowired private lateinit var releaseWorker: GitHubReleaseDraftWorker
	@Autowired private lateinit var activityService: GitHubReleaseActivityService
	@Autowired private lateinit var generationPersistence: GenerationPersistence
	@Autowired private lateinit var releasePersistence: GitHubReleasePersistence
	@Autowired private lateinit var generationWorkflowService: GenerationWorkflowService
	@Autowired private lateinit var github: ScriptedGitHubClient
	@Autowired private lateinit var model: ScriptedGenerationModelGateway
	@Autowired private lateinit var agentWorker: AgentRunWorker
	@Autowired private lateinit var agentModel: ScriptedAgentDecisionGateway
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devContext: DevContext

	private val generationWorker: GenerationRunWorker
		get() = GenerationRunWorker(
			generationPersistence,
			generationWorkflowService,
			model,
			workerId = "release-e2e-generation",
		)

	@BeforeEach
	fun isolateScenario() {
		/*
		 * Every fixture uses unique installation and repository identifiers. Existing
		 * integration tests may share the Testcontainers database, so do not delete
		 * rows that another test class created or that a durable foreign key protects.
		 */
		jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set status = 'FAILED', error_code = 'TEST_ISOLATION',
			    claimed_by = null, claimed_at = null, heartbeat_at = null,
			    finished_at = coalesce(finished_at, now()), updated_at = now()
			where status in ('QUEUED', 'RESOLVING', 'GENERATING')
			""".trimIndent(),
		)
		jdbcTemplate.update(
			"""
			update generation_runs
			set status = 'FAILED', error_code = 'TEST_ISOLATION',
			    claimed_by = null, claimed_at = null, heartbeat_at = null,
			    finished_at = coalesce(finished_at, now()), updated_at = now()
			where status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			""".trimIndent(),
		)
		jdbcTemplate.update(
			"""
			update agent_runs
			set status = 'FAILED', failure_code = 'TEST_ISOLATION',
			    claimed_by = null, claimed_at = null,
			    finished_at = coalesce(finished_at, now()), updated_at = now()
			where status in ('QUEUED', 'RUNNING')
			""".trimIndent(),
		)
		github.reset()
		model.reset()
		agentModel.reset()
		jdbcTemplate.update(
			"""
			update workspaces
			set plan = 'founding', entitlement_status = 'active',
			    access_mode = 'full', updated_at = now()
			where id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
		)
	}

	@Test
	fun `pushes stay observational and the second exact release produces one review ready pack`() {
		val fixture = bindRepository()
		val firstHead = "1".repeat(40)
		val pullRequestCommit = "7".repeat(40)
		val secondHead = "2".repeat(40)
		val releaseCommit = GitHubCommit(
			sha = secondHead,
			message = "Add release-triggered changelog\n\nPrepare an inspectable draft after a tag.",
			author = "plot",
			committedAt = Instant.parse("2026-07-30T01:00:00Z"),
			url = "https://github.com/${fixture.owner}/${fixture.repository}/commit/$secondHead",
		)
		val pullRequest = GitHubPullRequest(
			id = 101,
			number = 17,
			title = "Monitor trustworthy GitHub releases",
			body = "Prepare a review-ready changelog only after an exact release boundary.",
			author = "octocat",
			url = "https://github.com/${fixture.owner}/${fixture.repository}/pull/17",
			baseBranch = "main",
			headBranch = "release-automation",
			createdAt = Instant.parse("2026-07-29T20:00:00Z"),
			updatedAt = Instant.parse("2026-07-30T00:55:00Z"),
			mergedAt = Instant.parse("2026-07-30T00:55:00Z"),
		)
		github.tag(fixture, "v1.0.0", firstHead)
		github.tag(fixture, "v1.1.0", secondHead)
		github.compare(
			fixture,
			firstHead,
			secondHead,
			GitHubCompareResult(
				status = "ahead",
				aheadBy = 2,
				commits = listOf(
					GitHubCommit(
						sha = pullRequestCommit,
						message = "Monitor release events",
						author = "octocat",
						committedAt = Instant.parse("2026-07-30T00:55:00Z"),
						url = "https://github.com/${fixture.owner}/${fixture.repository}/commit/$pullRequestCommit",
					),
					releaseCommit,
				),
				files = listOf(GitHubChangedFile(
					filename = "apps/api/src/main/kotlin/com/plot/api/github/GitHubWebhookService.kt",
					previousFilename = null,
					status = "modified",
					additions = 12,
					deletions = 1,
					patch = "@@ release automation @@",
				)),
				filesTruncated = false,
			),
		)
		github.pullRequests(fixture, pullRequestCommit, listOf(pullRequest))

		repeat(3) { index ->
			val delivery = webhookService.accept(push(fixture, "push-$index-${UUID.randomUUID()}"))
			assertEquals(GitHubWebhookDisposition.OBSERVED, delivery.disposition)
		}
		assertCounts(fixture, requests = 0, runs = 0, packs = 0)
		assertEquals(0, model.totalCalls)

		webhookService.accept(tag(fixture, "first-${UUID.randomUUID()}", "v1.0.0", firstHead))
		assertEquals(1, releaseWorker.drain())
		val first = release("v1.0.0", fixture)
		assertEquals(GitHubReleaseDraftStatus.NEEDS_RANGE, first.status)
		assertEquals(firstHead, first.headSha)
		assertNull(first.baseSha)
		assertNull(first.generationRunId)
		assertCounts(fixture, requests = 1, runs = 0, packs = 0)
		assertEquals(0, model.totalCalls)
		jdbcTemplate.update(
			"update github_release_draft_requests set created_at = now() - interval '1 second' where id = ?",
			first.id,
		)

		val secondDeliveryId = "second-${UUID.randomUUID()}"
		webhookService.accept(tag(fixture, secondDeliveryId, "v1.1.0", secondHead))
		assertEquals(1, releaseWorker.drain())
		val generating = release("v1.1.0", fixture)
		assertEquals(GitHubReleaseDraftStatus.GENERATING, generating.status)
		assertEquals(firstHead, generating.baseSha)
		assertEquals(secondHead, generating.headSha)
		assertEquals("PREVIOUS_RELEASE_HEAD", generating.boundaryReason)
		val agentRunId = assertNotNull(generating.agentRunId)
		assertNull(generating.generationRunId)
		assertEquals(listOf("$firstHead...$secondHead"), github.comparisons)
		val expectedPullRequestKey =
			"release:v1.1.0:$firstHead...$secondHead:pull_request:${pullRequest.id}"
		val expectedCommitKey = "release:v1.1.0:$firstHead...$secondHead:commit:$secondHead"
		val evidence = releaseEvidence(generating.id)
		assertEquals(listOf("pull_request", "commit"), evidence.map { it.sourceKind })
		assertEquals(listOf(expectedPullRequestKey, expectedCommitKey), evidence.map { it.externalObjectKey })
		assertEquals(listOf(fixture.scopeId, fixture.scopeId), evidence.map { it.sourceScopeId })
		assertTrue(evidence.all { it.repositoryIdentity == "${fixture.owner}/${fixture.repository}" })
		assertEquals(
			listOf(pullRequest.url, releaseCommit.url),
			evidence.map { it.canonicalUrl },
		)
		assertEquals(
			listOf(pullRequest.body, releaseCommit.message.substringAfter("\n\n")),
			evidence.map { it.body.substringBefore("\n\nChanged files:") },
		)
		assertTrue(evidence[0].metadata.contains("\"pullRequestId\": ${pullRequest.id}"))
		assertTrue(evidence[1].metadata.contains("\"sha\": \"$secondHead\""))
		assertTrue(evidence.all { it.metadata.contains("\"releaseTag\": \"v1.1.0\"") })

		assertTrue(evidence.all { it.releaseObservationId == it.membershipObservationId })
		assertEquals(2, boundEvidence(generating.id))
		assertCounts(fixture, requests = 2, runs = 0, packs = 0)

		agentModel.scriptedDecision = { request ->
			AgentDecision(
				action = AgentDecisionAction.CREATE_ARTIFACT,
				selectedInputIds = request.inputs.map { it.id },
			)
		}
		assertEquals(1, agentWorker.drain())
		val linked = release("v1.1.0", fixture)
		assertEquals(agentRunId, linked.agentRunId)
		assertNull(linked.generationRunId)
		assertEquals(1, agentRunIdCount(agentRunId))
		assertCounts(fixture, requests = 2, runs = 1, packs = 0)

		assertEquals(2, generationWorker.drain())
		jdbcTemplate.update("update agent_runs set next_attempt_at = now() where id = ?", agentRunId)
		assertEquals(1, agentWorker.drain())
		releaseWorker.reconcile()
		val ready = release("v1.1.0", fixture)
		assertEquals(GitHubReleaseDraftStatus.READY, ready.status)
		val artifactId = assertNotNull(artifactId(ready.id))
		val generationRunId = assertNotNull(ready.generationRunId)
		val inputs = generationInputs(generationRunId)
		assertEquals(evidence.map { it.writingBlockId }, inputs.map { it.writingBlockId })
		assertEquals(evidence.map { it.sourceKind }, inputs.map { it.sourceKind })
		assertEquals(evidence.map { it.canonicalUrl }, inputs.map { it.originalUrl })
		assertEquals(evidence.map { it.body }, inputs.map { it.snapshotBody })
		assertEquals(evidence.map { it.contentHash }, inputs.map { it.contentHash })
		assertEquals(listOf(0, 1), inputs.map { it.orderIndex })
		assertTrue(inputs.all { it.generationRunId == generationRunId })
		assertEquals(inputs.size, inputs.map { it.generationInputId }.distinct().size)
		assertEquals(1, model.writerCalls.get())
		assertEquals(1, model.reviewerCalls.get())
		assertCounts(fixture, requests = 2, runs = 1, packs = 1)

		val activity = assertNotNull(activityService.latest(fixture.scopeId))
		assertEquals(ready.id, activity.id)
		assertEquals(GitHubReleaseDraftStatus.READY, activity.status)
		assertEquals(generationRunId, activity.generationRunId)
		assertEquals(artifactId, activity.artifactId)

		// Both a GitHub redelivery and the equivalent release.published event converge
		// on the same workspace/scope/tag request and cannot invoke the model again.
		webhookService.accept(tag(fixture, secondDeliveryId, "v1.1.0", secondHead))
		webhookService.accept(releasePublished(fixture, "release-${UUID.randomUUID()}", "v1.1.0"))
		assertEquals(0, releaseWorker.drain())
		assertEquals(1, requestCount(fixture, "v1.1.0"))
		assertEquals(0, generationAttemptCount(ready.id))
		assertEquals(1, model.writerCalls.get())
		assertEquals(1, model.reviewerCalls.get())
		assertCounts(fixture, requests = 2, runs = 1, packs = 1)
	}

	@Test
	fun `an unresolved or empty range never invokes generation`() {
		val fixture = bindRepository()
		val firstHead = "3".repeat(40)
		val secondHead = "4".repeat(40)
		github.tag(fixture, "v2.0.0", firstHead)
		github.tag(fixture, "v2.0.1", secondHead)
		github.compare(
			fixture,
			firstHead,
			secondHead,
			GitHubCompareResult(
				status = "ahead",
				aheadBy = 1,
				commits = listOf(GitHubCommit(
					sha = secondHead,
					message = "   ",
					author = null,
					committedAt = null,
					url = "https://github.com/${fixture.owner}/${fixture.repository}/commit/$secondHead",
				)),
				files = emptyList(),
				filesTruncated = false,
			),
		)

		webhookService.accept(tag(fixture, "empty-first-${UUID.randomUUID()}", "v2.0.0", firstHead))
		releaseWorker.drain()
		val first = release("v2.0.0", fixture)
		assertEquals(GitHubReleaseDraftStatus.NEEDS_RANGE, first.status)
		jdbcTemplate.update(
			"update github_release_draft_requests set created_at = now() - interval '1 second' where id = ?",
			first.id,
		)
		webhookService.accept(tag(fixture, "empty-second-${UUID.randomUUID()}", "v2.0.1", secondHead))
		releaseWorker.drain()

		val empty = release("v2.0.1", fixture)
		assertEquals(GitHubReleaseDraftStatus.NO_ACTIVITY, empty.status)
		assertNull(empty.generationRunId)
		assertCounts(fixture, requests = 2, runs = 0, packs = 0)
		assertEquals(0, model.totalCalls)
	}

	@Test
	fun `transient retry and stale claim recovery keep the original release request`() {
		val retryFixture = bindRepository()
		val retryHead = "5".repeat(40)
		github.tag(retryFixture, "v3.0.0", retryHead)
		github.failNextTagResolution(retryFixture)
		webhookService.accept(tag(retryFixture, "retry-${UUID.randomUUID()}", "v3.0.0", retryHead))

		assertEquals(1, releaseWorker.drain())
		val scheduled = release("v3.0.0", retryFixture)
		assertEquals(GitHubReleaseDraftStatus.QUEUED, scheduled.status)
		jdbcTemplate.update(
			"update github_release_draft_requests set next_attempt_at = now() where id = ?",
			scheduled.id,
		)
		assertEquals(1, releaseWorker.drain())
		val retried = release("v3.0.0", retryFixture)
		assertEquals(scheduled.id, retried.id)
		assertEquals(GitHubReleaseDraftStatus.NEEDS_RANGE, retried.status)
		assertEquals(1, requestCount(retryFixture, "v3.0.0"))

		val recoveryFixture = bindRepository()
		val recoveryHead = "6".repeat(40)
		github.tag(recoveryFixture, "v4.0.0", recoveryHead)
		webhookService.accept(tag(recoveryFixture, "recover-${UUID.randomUUID()}", "v4.0.0", recoveryHead))
		val requestId = release("v4.0.0", recoveryFixture).id
		jdbcTemplate.update(
			"""
			update github_release_draft_requests
			set status = 'RESOLVING', transition_version = transition_version + 1,
			    attempt_count = attempt_count + 1, claimed_by = 'dead-worker',
			    claimed_at = now() - interval '10 minutes',
			    heartbeat_at = now() - interval '10 minutes'
			where id = ?
			""".trimIndent(),
			requestId,
		)
		assertEquals(1, releaseWorker.recover())
		assertEquals(1, releaseWorker.drain())
		val recovered = release("v4.0.0", recoveryFixture)
		assertEquals(requestId, recovered.id)
		assertEquals(GitHubReleaseDraftStatus.NEEDS_RANGE, recovered.status)
		assertEquals(1, requestCount(recoveryFixture, "v4.0.0"))
		assertEquals(0, model.totalCalls)
	}

	private fun bindRepository(): RepositoryFixture {
		val seed = System.nanoTime()
		val installationId = seed.coerceAtLeast(10)
		val repositoryId = (seed + 1).coerceAtLeast(11)
		val owner = "plot-labs"
		val repository = "release-e2e-$repositoryId"
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into connections (
			 id, workspace_id, provider, connection_kind, external_connection_key,
			 status, created_by_user_id, created_at, updated_at
			) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())
			""".trimIndent(),
			connectionId,
			devContext.devWorkspaceId,
			installationId.toString(),
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces (
			 id, workspace_id, provider, namespace_kind, external_namespace_key,
			 status, created_at, updated_at
			) values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			devContext.devWorkspaceId,
			"installation:$installationId",
		)
		jdbcTemplate.update(
			"""
			insert into connection_namespace_bindings (
			 id, workspace_id, provider, connection_id, source_namespace_id,
			 status, valid_from, created_at, updated_at
			) values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())
			""".trimIndent(),
			bindingId,
			devContext.devWorkspaceId,
			connectionId,
			namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes (
			 id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, external_key, display_name, metadata, status, created_at, updated_at
			) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, ?,
			 '{"defaultBranch":"main"}'::jsonb, 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			repositoryId.toString(),
			"$owner/$repository",
			"$owner/$repository",
		)
		return RepositoryFixture(installationId, repositoryId, owner, repository, scopeId).also(github::expect)
	}

	private fun push(fixture: RepositoryFixture, deliveryId: String) = ParsedGitHubWebhook(
		externalDeliveryId = deliveryId,
		eventType = "push",
		eventAction = null,
		installationId = fixture.installationId,
		repositoryId = fixture.repositoryId,
		ref = "refs/heads/main",
		beforeSha = "0".repeat(40),
		afterSha = "9".repeat(40),
		tagName = null,
		refCreated = false,
		refDeleted = false,
		forced = false,
		payloadHash = "a".repeat(64),
	)

	private fun tag(
		fixture: RepositoryFixture,
		deliveryId: String,
		tagName: String,
		headSha: String,
	) = ParsedGitHubWebhook(
		externalDeliveryId = deliveryId,
		eventType = "push",
		eventAction = null,
		installationId = fixture.installationId,
		repositoryId = fixture.repositoryId,
		ref = "refs/tags/$tagName",
		beforeSha = "0".repeat(40),
		afterSha = headSha,
		tagName = tagName,
		refCreated = true,
		refDeleted = false,
		forced = false,
		payloadHash = "b".repeat(64),
	)

	private fun releasePublished(
		fixture: RepositoryFixture,
		deliveryId: String,
		tagName: String,
	) = ParsedGitHubWebhook(
		externalDeliveryId = deliveryId,
		eventType = "release",
		eventAction = "published",
		installationId = fixture.installationId,
		repositoryId = fixture.repositoryId,
		ref = null,
		beforeSha = null,
		afterSha = null,
		tagName = tagName,
		refCreated = null,
		refDeleted = null,
		forced = null,
		payloadHash = "c".repeat(64),
	)

	private fun release(tagName: String, fixture: RepositoryFixture): GitHubReleaseDraftRequest =
		assertNotNull(releasePersistence.findLatest(fixture.scopeId, devContext.devWorkspaceId)).also {
			assertEquals(tagName, it.tagName)
		}

	private fun releaseEvidence(requestId: UUID): List<ReleaseEvidenceIdentity> = jdbcTemplate.query(
		"""
		select e.writing_block_id, e.observation_id, ws.last_observation_id,
		       ws.source_scope_id, s.external_key, b.source_kind,
		       b.external_object_key, b.canonical_url, b.body, b.content_hash, b.metadata::text
		from github_release_draft_evidence e
		join writing_blocks b on b.workspace_id = e.workspace_id and b.id = e.writing_block_id
		join writing_block_scopes ws on ws.workspace_id = b.workspace_id
		  and ws.writing_block_id = b.id
		join source_scopes s on s.workspace_id = ws.workspace_id and s.id = ws.source_scope_id
		where e.request_id = ?
		order by e.order_index
		""".trimIndent(),
		{ rs, _ ->
			ReleaseEvidenceIdentity(
				writingBlockId = rs.getObject(1, UUID::class.java),
				releaseObservationId = rs.getObject(2, UUID::class.java),
				membershipObservationId = rs.getObject(3, UUID::class.java),
				sourceScopeId = rs.getObject(4, UUID::class.java),
				repositoryIdentity = rs.getString(5),
				sourceKind = rs.getString(6),
				externalObjectKey = rs.getString(7),
				canonicalUrl = rs.getString(8),
				body = rs.getString(9),
				contentHash = rs.getString(10),
				metadata = rs.getString(11),
			)
		},
		requestId,
	)

	private fun generationInputs(runId: UUID): List<GenerationInputIdentity> = jdbcTemplate.query(
		"""
		select id, generation_run_id, writing_block_id, source_kind, original_url,
		       snapshot_body, content_hash, order_index
		from generation_inputs
		where generation_run_id = ?
		order by order_index
		""".trimIndent(),
		{ rs, _ ->
			GenerationInputIdentity(
				generationInputId = rs.getObject(1, UUID::class.java),
				generationRunId = rs.getObject(2, UUID::class.java),
				writingBlockId = rs.getObject(3, UUID::class.java),
				sourceKind = rs.getString(4),
				originalUrl = rs.getString(5),
				snapshotBody = rs.getString(6),
				contentHash = rs.getString(7),
				orderIndex = rs.getInt(8),
			)
		},
		runId,
	)

	private fun boundEvidence(requestId: UUID): Int = count(
		"select count(*) from github_release_draft_evidence where request_id = ?",
		requestId,
	)

	private fun artifactId(requestId: UUID): UUID? = jdbcTemplate.query(
		"select id from content_packs where workspace_id = ? and release_request_id = ?",
		{ rs, _ -> rs.getObject(1, UUID::class.java) },
		devContext.devWorkspaceId,
		requestId,
	).singleOrNull()

	private fun generationAttemptCount(requestId: UUID): Int = count(
		"select count(*) from github_release_generation_attempts where request_id = ?",
		requestId,
	)

	private fun agentRunIdCount(agentRunId: UUID): Int = count(
		"select count(*) from agent_runs where id = ?",
		agentRunId,
	)

	private fun requestCount(fixture: RepositoryFixture, tagName: String): Int = count(
		"""
		select count(*) from github_release_draft_requests
		where workspace_id = ? and source_scope_id = ? and tag_name = ?
		""".trimIndent(),
		devContext.devWorkspaceId,
		fixture.scopeId,
		tagName,
	)

	private fun assertCounts(fixture: RepositoryFixture, requests: Int, runs: Int, packs: Int) {
		assertEquals(requests, count(
			"select count(*) from github_release_draft_requests where workspace_id = ? and source_scope_id = ?",
			devContext.devWorkspaceId,
			fixture.scopeId,
		))
		assertEquals(runs, count(
			"""
			select count(*)
			from generation_runs g
			where g.workspace_id = ?
			  and (
			    g.source_scope_id = ?
			    or g.agent_run_id in (
			      select agent_run_id
			      from github_release_draft_requests
			      where workspace_id = ? and source_scope_id = ? and agent_run_id is not null
			    )
			  )
			""".trimIndent(),
			devContext.devWorkspaceId,
			fixture.scopeId,
			devContext.devWorkspaceId,
			fixture.scopeId,
		))
		assertEquals(packs, count(
			"""
			select count(*)
			from content_packs p
			join github_release_draft_requests r on r.workspace_id = p.workspace_id
			 and r.id = p.release_request_id
			where r.workspace_id = ? and r.source_scope_id = ?
			""".trimIndent(),
			devContext.devWorkspaceId,
			fixture.scopeId,
		))
	}

	private fun count(sql: String, vararg arguments: Any): Int =
		jdbcTemplate.queryForObject(sql, Int::class.java, *arguments)!!

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun scriptedGitHubClient() = ScriptedGitHubClient()

		@Bean
		@Primary
		fun scriptedGenerationModelGateway() = ScriptedGenerationModelGateway()

		@Bean
		@Primary
		fun scriptedAgentDecisionGateway() = ScriptedAgentDecisionGateway()

		@Bean
		@Primary
		fun noOpReleaseDispatcher(): GitHubReleaseDraftDispatcher = GitHubReleaseDraftDispatcher { }

		@Bean
		@Primary
		fun noOpGenerationDispatcher(): GenerationRunDispatcher =
			GenerationRunDispatcher(TaskExecutor { _ -> }) { false }
	}
}

data class RepositoryFixture(
	val installationId: Long,
	val repositoryId: Long,
	val owner: String,
	val repository: String,
	val scopeId: UUID,
)

private data class ReleaseEvidenceIdentity(
	val writingBlockId: UUID,
	val releaseObservationId: UUID,
	val membershipObservationId: UUID,
	val sourceScopeId: UUID,
	val repositoryIdentity: String,
	val sourceKind: String,
	val externalObjectKey: String,
	val canonicalUrl: String,
	val body: String,
	val contentHash: String,
	val metadata: String,
)

private data class GenerationInputIdentity(
	val generationInputId: UUID,
	val generationRunId: UUID,
	val writingBlockId: UUID,
	val sourceKind: String,
	val originalUrl: String,
	val snapshotBody: String,
	val contentHash: String,
	val orderIndex: Int,
)

class ScriptedGitHubClient : GitHubClient {
	private val expectedRepositories = ConcurrentHashMap.newKeySet<RepositoryKey>()
	private val tagHeads = ConcurrentHashMap<TagKey, String>()
	private val compareResults = ConcurrentHashMap<CompareKey, GitHubCompareResult>()
	private val pullRequests = ConcurrentHashMap<CommitKey, List<GitHubPullRequest>>()
	private val tagResolutionFailures = ConcurrentHashMap.newKeySet<RepositoryKey>()
	val comparisons = mutableListOf<String>()

	override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> {
		val repositories = expectedRepositories.filter { it.installationId == installationId }
		require(repositories.isNotEmpty()) { "Unexpected GitHub installation: $installationId" }
		return repositories.map {
			GitHubRepository(
				id = it.repositoryId,
				owner = it.owner,
				name = it.repository,
				url = "https://github.com/${it.owner}/${it.repository}",
				defaultBranch = "main",
			)
		}
	}

	override fun listClosedPullRequests(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		pageCap: Int,
	): List<GitHubPullRequest> {
		checkedKey(installationId, repositoryId, owner, repository)
		return emptyList()
	}

	override fun resolveTagCommit(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		tagName: String,
	): String {
		val repositoryKey = checkedKey(installationId, repositoryId, owner, repository)
		if (tagResolutionFailures.remove(repositoryKey)) {
			throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"GITHUB_PROVIDER_UNAVAILABLE",
				"GitHub is temporarily unavailable",
			)
		}
		return requireNotNull(tagHeads[TagKey(repositoryKey, tagName)])
	}

	override fun compareCommits(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		baseSha: String,
		headSha: String,
		pageCap: Int,
	): GitHubCompareResult {
		val repositoryKey = checkedKey(installationId, repositoryId, owner, repository)
		comparisons += "$baseSha...$headSha"
		return requireNotNull(compareResults[CompareKey(repositoryKey, baseSha, headSha)])
	}

	override fun listPullRequestsForCommit(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		commitSha: String,
	): List<GitHubPullRequest> =
		pullRequests[CommitKey(checkedKey(installationId, repositoryId, owner, repository), commitSha)].orEmpty()

	fun expect(fixture: RepositoryFixture) {
		expectedRepositories += fixture.key
	}

	fun tag(fixture: RepositoryFixture, tagName: String, headSha: String) {
		tagHeads[TagKey(fixture.key, tagName)] = headSha
	}

	fun compare(fixture: RepositoryFixture, baseSha: String, headSha: String, result: GitHubCompareResult) {
		compareResults[CompareKey(fixture.key, baseSha, headSha)] = result
	}

	fun pullRequests(fixture: RepositoryFixture, commitSha: String, values: List<GitHubPullRequest>) {
		pullRequests[CommitKey(fixture.key, commitSha)] = values
	}

	fun failNextTagResolution(fixture: RepositoryFixture) {
		tagResolutionFailures += fixture.key
	}

	fun reset() {
		expectedRepositories.clear()
		tagHeads.clear()
		compareResults.clear()
		pullRequests.clear()
		tagResolutionFailures.clear()
		comparisons.clear()
	}

	private fun checkedKey(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
	): RepositoryKey = RepositoryKey(installationId, repositoryId, owner, repository).also {
		require(it in expectedRepositories) { "Unexpected GitHub repository identity: $it" }
	}
}

private val RepositoryFixture.key: RepositoryKey
	get() = RepositoryKey(installationId, repositoryId, owner, repository)

private data class RepositoryKey(
	val installationId: Long,
	val repositoryId: Long,
	val owner: String,
	val repository: String,
)

private data class TagKey(val repository: RepositoryKey, val tagName: String)
private data class CompareKey(val repository: RepositoryKey, val baseSha: String, val headSha: String)
private data class CommitKey(val repository: RepositoryKey, val commitSha: String)

class ScriptedGenerationModelGateway : GenerationModelGateway {
	val writerCalls = AtomicInteger()
	val reviewerCalls = AtomicInteger()
	val totalCalls: Int
		get() = writerCalls.get() + reviewerCalls.get()

	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> {
		writerCalls.incrementAndGet()
		assertTrue(request.evidence.isNotEmpty())
		return result(WriterOutput(listOf(WriterSentence(
			"Plot now prepares a review-ready changelog after a trustworthy GitHub release boundary.",
		))))
	}

	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> {
		reviewerCalls.incrementAndGet()
		val sentence = request.sentences.single()
		val evidence = request.evidence.first()
		return result(ReviewerOutput(listOf(SentenceReview(
			sentenceId = sentence.id,
			verdict = ReviewVerdict.SUPPORTED,
			evidenceIds = listOf(evidence.id),
		))))
	}

	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> =
		error("The release E2E must not require a rewrite")

	fun reset() {
		writerCalls.set(0)
		reviewerCalls.set(0)
	}

	private fun <T : Any> result(value: T) = ModelCallResult(
		value,
		ModelCallMetadata(
			responseId = "release-e2e",
			actualModel = "scripted",
			finishReason = "stop",
			promptTokens = 1,
			completionTokens = 1,
			totalTokens = 2,
			latency = Duration.ofMillis(1),
			observationAttributes = emptyMap(),
		),
	)
}
