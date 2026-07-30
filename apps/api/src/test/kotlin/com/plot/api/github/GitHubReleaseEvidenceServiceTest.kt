package com.plot.api.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.common.ApiException
import com.plot.api.common.WorkspacePrincipal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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
@Import(TestcontainersConfiguration::class, GitHubReleaseEvidenceServiceTest.Config::class)
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.github.max-changed-files=2",
	"plot.github.max-commit-pull-request-lookups=3",
	"plot.github.max-diff-characters=240",
	"plot.github.max-release-pull-requests=2",
	"plot.github.max-release-evidence-blocks=4",
	"plot.github.max-release-title-characters=64",
	"plot.github.max-release-body-characters=512",
	"plot.github.max-release-evidence-characters=768",
])
class GitHubReleaseEvidenceServiceTest {
	@Autowired private lateinit var service: GitHubReleaseEvidenceService
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var client: FakeEvidenceGitHubClient

	@BeforeEach
	fun reset() {
		client.reset()
	}

	@Test
	fun `collect persists bounded deterministic pr and uncovered commit evidence in supplied workspace`() {
		val fixture = releaseFixture()
		client.pullRequestsByCommit["commit-b"] = listOf(pullRequest(22, 2), pullRequest(22, 2))
		client.pullRequestsByCommit["commit-a"] = listOf(pullRequest(11, 1))
		val comparison = GitHubCompareResult(
			status = "ahead",
			aheadBy = 3,
			commits = listOf(commit("commit-b", "Second"), commit("commit-a", "First"), commit("commit-c", "Third")),
			files = listOf(
				changedFile("src/one.kt", "patch-one-${"x".repeat(100)}"),
				changedFile("src/two.kt", "patch-two-${"y".repeat(100)}"),
				changedFile("src/ignored.kt", "must-not-appear"),
			),
			filesTruncated = true,
		)

		val result = service.collect(
			principal = WorkspacePrincipal(fixture.context.workspaceId, fixture.context.createdByUserId),
			context = fixture.context,
			request = fixture.request,
			range = GitHubReleaseRange("base", "head", "PREVIOUS_RELEASE_HEAD", comparison),
		)

		assertEquals(listOf("commit-b", "commit-a", "commit-c"), client.lookedUpCommits)
		assertEquals(listOf("pull_request", "pull_request", "commit"), result.writingBlockIds.map { blockKind(it) })
		assertEquals(
			listOf(
				"release:v2:base...head:pull_request:11",
				"release:v2:base...head:pull_request:22",
				"release:v2:base...head:commit:commit-c",
			),
			result.writingBlockIds.map { externalKey(it) },
		)
		assertTrue(result.writingBlockIds.all { blockWorkspace(it) == fixture.context.workspaceId })
		assertEquals(
			result.writingBlockIds,
			jdbcTemplate.query(
				"""
				select writing_block_id from writing_block_scopes
				where workspace_id = ? and source_scope_id = ? and last_observation_id = ?
				""".trimIndent(),
				{ rs, _ -> rs.getObject(1, UUID::class.java) },
				fixture.context.workspaceId,
				fixture.context.sourceScopeId,
				result.observationId,
			).sortedBy { result.writingBlockIds.indexOf(it) },
		)
		val observation = jdbcTemplate.queryForMap(
			"""
			select authority_owner, coverage_key, observation_mode, status
			from source_observations where workspace_id = ? and id = ?
			""".trimIndent(),
			fixture.context.workspaceId,
			result.observationId,
		)
		assertEquals("GITHUB_RELEASE", observation["authority_owner"])
		assertEquals("release:v2:base...head", observation["coverage_key"])
		assertEquals("PARTIAL", observation["observation_mode"])
		assertEquals("COMPLETED", observation["status"])
		val stored = result.writingBlockIds.map { id ->
			jdbcTemplate.queryForMap("select body, metadata::text from writing_blocks where id = ?", id)
		}
		val combinedBodies = stored.joinToString("\n") { it["body"].toString() }
		assertTrue(combinedBodies.contains("src/one.kt"))
		assertTrue(combinedBodies.contains("src/two.kt"))
		assertTrue(!combinedBodies.contains("src/ignored.kt"))
		assertTrue(!combinedBodies.contains("must-not-appear"))
		assertTrue(combinedBodies.contains(CHANGED_FILE_DIFF_TRUNCATED_MARKER))
		assertTrue(
			combinedBodies.substringAfter("Changed files:\n", "").substringBefore("\n\n").length <= 240,
		)
		assertTrue(stored.all { it["metadata"].toString().contains("\"baseSha\": \"base\"") })
		assertTrue(stored.all { it["metadata"].toString().contains("\"headSha\": \"head\"") })
		assertTrue(stored.all { it["metadata"].toString().contains("\"releaseTag\": \"v2\"") })
		assertEquals(1, stored.count { it["metadata"].toString().contains("\"changedFiles\"") })
		assertTrue(stored.all { it["metadata"].toString().contains("\"changedFileDiffTruncated\": true") })

		val repeated = service.collect(
			principal = WorkspacePrincipal(fixture.context.workspaceId, fixture.context.createdByUserId),
			context = fixture.context,
			request = fixture.request,
			range = GitHubReleaseRange("base", "head", "PREVIOUS_RELEASE_HEAD", comparison),
		)
		assertEquals(result.writingBlockIds, repeated.writingBlockIds)
	}

	@Test
	fun `open and wrong base pull requests do not hide their uncovered commits`() {
		val fixture = releaseFixture()
		client.pullRequestsByCommit["open-commit"] = listOf(
			pullRequest(31, 31).copy(mergedAt = null),
		)
		client.pullRequestsByCommit["wrong-base-commit"] = listOf(
			pullRequest(32, 32).copy(baseBranch = "release"),
		)

		val result = service.collect(
			WorkspacePrincipal(fixture.context.workspaceId, fixture.context.createdByUserId),
			fixture.context,
			fixture.request,
			releaseRange(listOf(
				commit("open-commit", "Open association"),
				commit("wrong-base-commit", "Wrong base association"),
			)),
		)

		assertEquals(listOf("commit", "commit"), result.writingBlockIds.map { blockKind(it) })
		assertEquals(
			listOf(
				"release:v2:base...head:commit:open-commit",
				"release:v2:base...head:commit:wrong-base-commit",
			),
			result.writingBlockIds.map { externalKey(it) },
		)
	}

	@Test
	fun `collect is idempotent for writing blocks and completes an empty observation without evidence`() {
		val fixture = releaseFixture()
		val range = GitHubReleaseRange(
			"base",
			"head",
			"PREVIOUS_RELEASE_HEAD",
			GitHubCompareResult("ahead", 1, listOf(commit("blank", "   ")), emptyList(), false),
		)

		val first = service.collect(
			WorkspacePrincipal(fixture.context.workspaceId, fixture.context.createdByUserId),
			fixture.context,
			fixture.request,
			range,
		)
		val second = service.collect(
			WorkspacePrincipal(fixture.context.workspaceId, fixture.context.createdByUserId),
			fixture.context,
			fixture.request,
			range,
		)

		assertEquals(emptyList(), first.writingBlockIds)
		assertEquals(emptyList(), second.writingBlockIds)
		assertTrue(first.observationId != second.observationId)
		assertEquals(2, jdbcTemplate.queryForObject(
			"""
			select count(*) from source_observations
			where workspace_id = ? and source_scope_id = ? and authority_owner = 'GITHUB_RELEASE'
			  and coverage_key = 'release:v2:base...head' and status = 'COMPLETED'
			""".trimIndent(),
			Int::class.java,
			fixture.context.workspaceId,
			fixture.context.sourceScopeId,
		))
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from writing_blocks where workspace_id = ? and source_namespace_id = ?",
			Int::class.java,
			fixture.context.workspaceId,
			fixture.context.sourceNamespaceId,
		))
	}

	@Test
	fun `provider failure leaves a durable failed observation without source content`() {
		val fixture = releaseFixture()
		client.failLookups = true
		val range = GitHubReleaseRange(
			"base",
			"head",
			"PREVIOUS_RELEASE_HEAD",
			GitHubCompareResult("ahead", 1, listOf(commit("private-sha", "Private body")), emptyList(), false),
		)

		assertFailsWith<IllegalStateException> {
			service.collect(
				WorkspacePrincipal(fixture.context.workspaceId, fixture.context.createdByUserId),
				fixture.context,
				fixture.request,
				range,
			)
		}

		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"""
			select status from source_observations
			where workspace_id = ? and source_scope_id = ? and authority_owner = 'GITHUB_RELEASE'
			order by generation desc limit 1
			""".trimIndent(),
			String::class.java,
			fixture.context.workspaceId,
			fixture.context.sourceScopeId,
		))
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from writing_blocks where workspace_id = ?",
			Int::class.java,
			fixture.context.workspaceId,
		))
	}

	@Test
	fun `distinct pull request fanout is rejected before any evidence block is persisted`() {
		val fixture = releaseFixture()
		client.pullRequestsByCommit["commit-a"] = listOf(
			pullRequest(11, 1),
			pullRequest(22, 2),
			pullRequest(33, 3),
		)
		val range = releaseRange(listOf(commit("commit-a", "Change")))

		assertEvidenceTooLarge(fixture, range)
	}

	@Test
	fun `multi lookup character fanout stops before requesting the next commit`() {
		val fixture = releaseFixture()
		client.pullRequestsByCommit["commit-a"] = listOf(
			pullRequest(11, 1).copy(body = "a".repeat(400)),
		)
		client.pullRequestsByCommit["commit-b"] = listOf(
			pullRequest(22, 2).copy(body = "b".repeat(400)),
			pullRequest(23, 3).copy(body = "private-${"c".repeat(400)}"),
		)
		val range = releaseRange(
			listOf(
				commit("commit-a", "One"),
				commit("commit-b", "Two"),
				commit("commit-c", "Must not be requested"),
			),
		)

		assertEvidenceTooLarge(fixture, range)
		assertEquals(listOf("commit-a", "commit-b"), client.lookedUpCommits)
	}

	@Test
	fun `one oversized provider body is rejected with a safe code`() {
		val fixture = releaseFixture()
		client.pullRequestsByCommit["commit-a"] = listOf(
			pullRequest(11, 1).copy(body = "s".repeat(513)),
		)
		val range = releaseRange(listOf(commit("commit-a", "Change")))

		val exception = assertEvidenceTooLarge(fixture, range)
		assertTrue(!exception.message.orEmpty().contains("s".repeat(32)))
	}

	@Test
	fun `release wide character cap is enforced before persistence`() {
		val fixture = releaseFixture()
		client.pullRequestsByCommit["commit-a"] = listOf(
			pullRequest(11, 1).copy(body = "a".repeat(400)),
			pullRequest(22, 2).copy(body = "b".repeat(400)),
		)
		val range = releaseRange(listOf(commit("commit-a", "Change")))

		assertEvidenceTooLarge(fixture, range)
	}

	@Test
	fun `release wide block cap rejects uncovered commit fanout`() {
		val fixture = releaseFixture()
		val range = releaseRange(
			listOf(
				commit("commit-a", "One"),
				commit("commit-b", "Two"),
				commit("commit-c", "Three"),
				commit("commit-d", "Four"),
				commit("commit-e", "Five"),
			),
		)

		assertEvidenceTooLarge(fixture, range)
	}

	@Test
	fun `a mid batch persistence conflict rolls back earlier blocks and fails the observation`() {
		val fixture = releaseFixture()
		val firstKey = "release:v2:base...head:pull_request:11"
		val conflictingKey = "release:v2:base...head:pull_request:22"
		jdbcTemplate.update(
			"""
			insert into writing_blocks (
			 id, workspace_id, source_namespace_id, external_object_key, source_origin, source_kind,
			 title, body, url, canonical_url, author, platform, metadata, content_hash,
			 source_created_at, source_updated_at, ingested_at, status, created_by_user_id, created_at, updated_at
			) values (?, ?, ?, ?, 'integration', 'pull_request', 'Old title', 'Old body',
			 'https://github.test/acme/plot/pull/2', 'https://github.test/acme/plot/pull/2',
			 'ada', 'github', '{}'::jsonb, 'old-hash', ?, ?, now(), 'ACTIVE', ?, now(), now())
			""".trimIndent(),
			UUID.randomUUID(),
			fixture.context.workspaceId,
			fixture.context.sourceNamespaceId,
			conflictingKey,
			java.sql.Timestamp.from(Instant.parse("2026-07-29T00:00:00Z")),
			java.sql.Timestamp.from(Instant.parse("2026-07-30T00:00:00Z")),
			fixture.context.createdByUserId,
		)
		client.pullRequestsByCommit["commit-a"] = listOf(pullRequest(11, 1), pullRequest(22, 2))

		assertFailsWith<IllegalStateException> {
			service.collect(
				WorkspacePrincipal(fixture.context.workspaceId, fixture.context.createdByUserId),
				fixture.context,
				fixture.request,
				releaseRange(listOf(commit("commit-a", "Change"))),
			)
		}

		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from writing_blocks where workspace_id = ? and external_object_key = ?",
			Int::class.java,
			fixture.context.workspaceId,
			firstKey,
		))
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from writing_blocks where workspace_id = ? and external_object_key = ?",
			Int::class.java,
			fixture.context.workspaceId,
			conflictingKey,
		))
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"""
			select status from source_observations
			where workspace_id = ? and source_scope_id = ? and authority_owner = 'GITHUB_RELEASE'
			order by generation desc limit 1
			""".trimIndent(),
			String::class.java,
			fixture.context.workspaceId,
			fixture.context.sourceScopeId,
		))
	}

	private fun releaseFixture(): ReleaseFixture {
		val workspaceId = UUID.randomUUID()
		val userId = UUID.randomUUID()
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val deliveryId = UUID.randomUUID()
		val requestId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into users (id, email, display_name, status, created_at, updated_at) values (?, ?, 'Release', 'ACTIVE', now(), now())",
			userId,
			"release-${userId}@example.test",
		)
		jdbcTemplate.update(
			"insert into workspaces (id, name, slug, created_by_user_id, status, created_at, updated_at) values (?, 'Release', ?, ?, 'ACTIVE', now(), now())",
			workspaceId,
			"release-${workspaceId}",
			userId,
		)
		jdbcTemplate.update(
			"""
			insert into connections
			(id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at)
			values (?, ?, 'GITHUB', 'APP_INSTALLATION', '77', 'ACTIVE', ?, now(), now())
			""".trimIndent(),
			connectionId,
			workspaceId,
			userId,
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', '77', 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			workspaceId,
		)
		jdbcTemplate.update(
			"""
			insert into connection_namespace_bindings
			(id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at)
			values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())
			""".trimIndent(),
			bindingId,
			workspaceId,
			connectionId,
			namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, external_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', '44', 'acme/plot', 'acme/plot', 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			workspaceId,
			namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into github_webhook_deliveries
			(id, external_delivery_id, event_type, payload_hash, disposition, received_at)
			values (?, ?, 'release', ?, 'QUEUED', now())
			""".trimIndent(),
			deliveryId,
			"delivery-${UUID.randomUUID()}",
			"a".repeat(64),
		)
		jdbcTemplate.update(
			"""
			insert into github_release_draft_requests
			(id, workspace_id, source_scope_id, initial_delivery_id, tag_name, status, created_at, updated_at)
			values (?, ?, ?, ?, 'v2', 'RESOLVING', now(), now())
			""".trimIndent(),
			requestId,
			workspaceId,
			scopeId,
			deliveryId,
		)
		val context = GitHubReleaseSourceContext(
			workspaceId,
			userId,
			connectionId,
			bindingId,
			namespaceId,
			scopeId,
			77,
			44,
			"acme",
			"plot",
			"main",
		)
		val request = GitHubReleaseDraftRequest(
			requestId,
			workspaceId,
			scopeId,
			deliveryId,
			"v2",
			null,
			null,
			null,
			GitHubReleaseDraftStatus.RESOLVING,
			1,
			0,
			null,
			null,
			null,
		)
		return ReleaseFixture(context, request)
	}

	private fun pullRequest(id: Long, number: Int) = GitHubPullRequest(
		id = id,
		number = number,
		title = "PR $number",
		body = "Customer-facing change $number",
		author = "ada",
		url = "https://github.test/acme/plot/pull/$number",
		baseBranch = "main",
		headBranch = "feature-$number",
		createdAt = Instant.parse("2026-07-29T00:00:00Z"),
		updatedAt = Instant.parse("2026-07-30T00:00:00Z"),
		mergedAt = Instant.parse("2026-07-30T00:00:00Z"),
	)

	private fun commit(sha: String, message: String) = GitHubCommit(
		sha,
		message,
		"ada",
		Instant.parse("2026-07-30T00:00:00Z"),
		"https://github.test/acme/plot/commit/$sha",
	)

	private fun changedFile(filename: String, patch: String) =
		GitHubChangedFile(filename, null, "modified", 3, 1, patch)

	private fun releaseRange(commits: List<GitHubCommit>) = GitHubReleaseRange(
		"base",
		"head",
		"PREVIOUS_RELEASE_HEAD",
		GitHubCompareResult("ahead", commits.size, commits, emptyList(), false),
	)

	private fun assertEvidenceTooLarge(
		fixture: ReleaseFixture,
		range: GitHubReleaseRange,
	): ApiException {
		val exception = assertFailsWith<ApiException> {
			service.collect(
				WorkspacePrincipal(fixture.context.workspaceId, fixture.context.createdByUserId),
				fixture.context,
				fixture.request,
				range,
			)
		}
		assertEquals("GITHUB_RELEASE_EVIDENCE_TOO_LARGE", exception.error)
		assertEquals("GitHub release evidence exceeds configured limits", exception.message)
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"""
			select status from source_observations
			where workspace_id = ? and source_scope_id = ? and authority_owner = 'GITHUB_RELEASE'
			order by generation desc limit 1
			""".trimIndent(),
			String::class.java,
			fixture.context.workspaceId,
			fixture.context.sourceScopeId,
		))
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from writing_blocks where workspace_id = ?",
			Int::class.java,
			fixture.context.workspaceId,
		))
		return exception
	}

	private fun blockKind(id: UUID): String =
		jdbcTemplate.queryForObject("select source_kind from writing_blocks where id = ?", String::class.java, id)!!

	private fun externalKey(id: UUID): String =
		jdbcTemplate.queryForObject("select external_object_key from writing_blocks where id = ?", String::class.java, id)!!

	private fun blockWorkspace(id: UUID): UUID =
		jdbcTemplate.queryForObject("select workspace_id from writing_blocks where id = ?", UUID::class.java, id)!!

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun fakeEvidenceGitHubClient(): FakeEvidenceGitHubClient = FakeEvidenceGitHubClient()
	}
}

class GitHubChangedFileDiffSummaryTest {
	@Test
	fun `exact entry boundary is complete and not marked truncated`() {
		val file = GitHubChangedFile("src/exact.kt", null, "modified", 2, 1, "patch")
		val entry = "modified src/exact.kt (+2 -1)\npatch"

		val result = buildChangedFileDiffSummary(listOf(file), maxChangedFiles = 1, characterLimit = entry.length)

		assertEquals(entry, result.text)
		assertEquals(false, result.truncated)
	}

	@Test
	fun `an oversized first entry never emits a partial header and includes an explicit marker`() {
		val file = GitHubChangedFile(
			"src/private-and-very-long-filename.kt",
			null,
			"modified",
			2,
			1,
			"secret-patch".repeat(100),
		)
		val limit = CHANGED_FILE_DIFF_TRUNCATED_MARKER.length + 8

		val result = buildChangedFileDiffSummary(listOf(file), maxChangedFiles = 1, characterLimit = limit)

		assertEquals(CHANGED_FILE_DIFF_TRUNCATED_MARKER, result.text)
		assertEquals(true, result.truncated)
		assertTrue(!result.text.contains("src/priv"))
	}
}

private data class ReleaseFixture(
	val context: GitHubReleaseSourceContext,
	val request: GitHubReleaseDraftRequest,
)

class FakeEvidenceGitHubClient : GitHubClient {
	val pullRequestsByCommit = mutableMapOf<String, List<GitHubPullRequest>>()
	val lookedUpCommits = mutableListOf<String>()
	var failLookups = false

	fun reset() {
		pullRequestsByCommit.clear()
		lookedUpCommits.clear()
		failLookups = false
	}

	override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> = error("not used")

	override fun listClosedPullRequests(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		pageCap: Int,
	): List<GitHubPullRequest> = error("not used")

	override fun listPullRequestsForCommit(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		commitSha: String,
	): List<GitHubPullRequest> {
		lookedUpCommits += commitSha
		if (failLookups) throw IllegalStateException("provider content is unavailable")
		return pullRequestsByCommit[commitSha].orEmpty()
	}
}
