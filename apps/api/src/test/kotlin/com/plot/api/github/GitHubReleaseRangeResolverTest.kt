package com.plot.api.github

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class GitHubReleaseRangeResolverTest {
	@Test
	fun acceptsNewestPreviousHeadThatIsAnExactAncestor() {
		val persistence = FakeReleasePersistence(
			previous = listOf(request(tagName = "v2", headSha = "newest"), request(tagName = "v1", headSha = "older")),
		)
		val client = FakeRangeGitHubClient(
			headSha = "current",
			comparisons = mapOf(
				"newest" to comparison(status = "ahead", aheadBy = 4),
				"older" to comparison(status = "ahead", aheadBy = 9),
			),
		)
		val resolver = DefaultGitHubReleaseRangeResolver(client, persistence, GitHubProperties(comparePageCap = 7))

		val result = assertIs<GitHubReleaseRangeResult.Resolved>(resolver.resolve(context(), request()))

		assertEquals("newest", result.range.baseSha)
		assertEquals("current", result.range.headSha)
		assertEquals("PREVIOUS_RELEASE_HEAD", result.range.boundaryReason)
		assertEquals(listOf("newest"), client.comparedBases)
		assertEquals(7, client.pageCaps.single())
		assertEquals(null, persistence.savedRange)
	}

	@Test
	fun skipsDivergedAndBehindCandidatesBeforeAcceptingAnOlderAncestor() {
		val persistence = FakeReleasePersistence(
			previous = listOf(
				request(tagName = "v3", headSha = "diverged"),
				request(tagName = "v2", headSha = "behind"),
				request(tagName = "v1", headSha = "ancestor"),
			),
		)
		val client = FakeRangeGitHubClient(
			headSha = "current",
			comparisons = mapOf(
				"diverged" to comparison(status = "diverged", aheadBy = 2),
				"behind" to comparison(status = "behind", aheadBy = 0),
				"ancestor" to comparison(status = "ahead", aheadBy = 1),
			),
		)
		val resolver = DefaultGitHubReleaseRangeResolver(client, persistence, GitHubProperties())

		val result = assertIs<GitHubReleaseRangeResult.Resolved>(resolver.resolve(context(), request()))

		assertEquals("ancestor", result.range.baseSha)
		assertEquals(listOf("diverged", "behind", "ancestor"), client.comparedBases)
	}

	@Test
	fun identicalCandidateReturnsNoActivityWithoutTryingOlderRows() {
		val persistence = FakeReleasePersistence(
			previous = listOf(request(tagName = "same", headSha = "current"), request(tagName = "older", headSha = "older")),
		)
		val client = FakeRangeGitHubClient(
			headSha = "current",
			comparisons = mapOf(
				"current" to comparison(status = "identical", aheadBy = 0),
				"older" to comparison(status = "ahead", aheadBy = 1),
			),
		)
		val resolver = DefaultGitHubReleaseRangeResolver(client, persistence, GitHubProperties())

		val result = assertIs<GitHubReleaseRangeResult.NoActivity>(resolver.resolve(context(), request()))

		assertEquals("current", result.baseSha)
		assertEquals("current", result.headSha)
		assertEquals("PREVIOUS_RELEASE_HEAD", result.boundaryReason)
		assertEquals(listOf("current"), client.comparedBases)
		assertEquals(null, persistence.finishedHead)
	}

	@Test
	fun observedTagPushHeadMustStillMatchLiveTagResolution() {
		val persistence = FakeReleasePersistence(previous = listOf(request(tagName = "v1", headSha = "base")))
		val client = FakeRangeGitHubClient(
			headSha = "moved-head",
			comparisons = mapOf("base" to comparison(status = "ahead", aheadBy = 1)),
		)
		val resolver = DefaultGitHubReleaseRangeResolver(client, persistence, GitHubProperties())

		val exception = assertFailsWith<GitHubReleasePermanentException> {
			resolver.resolve(context(), request(observedHeadSha = "observed-head"))
		}

		assertEquals("GITHUB_TAG_MOVED", exception.safeErrorCode)
		assertEquals(emptyList(), client.comparedBases)
	}

	@Test
	fun publishedReleaseWithoutObservedShaUsesTheResolvedLiveTagBoundary() {
		val persistence = FakeReleasePersistence(previous = listOf(request(tagName = "v1", headSha = "base")))
		val client = FakeRangeGitHubClient(
			headSha = "live-head",
			comparisons = mapOf("base" to comparison(status = "ahead", aheadBy = 1)),
		)
		val resolver = DefaultGitHubReleaseRangeResolver(client, persistence, GitHubProperties())

		val result = assertIs<GitHubReleaseRangeResult.Resolved>(
			resolver.resolve(context(), request(observedHeadSha = null)),
		)

		assertEquals("live-head", result.range.headSha)
	}

	@Test
	fun firstObservedTagPersistsItsExactHeadAndReturnsNeedsRange() {
		val persistence = FakeReleasePersistence(previous = emptyList())
		val client = FakeRangeGitHubClient(headSha = "first-head", comparisons = emptyMap())
		val resolver = DefaultGitHubReleaseRangeResolver(client, persistence, GitHubProperties())
		val current = request(tagName = "v1")

		val result = assertIs<GitHubReleaseRangeResult.NeedsRange>(resolver.resolve(context(), current))

		assertEquals("first-head", result.headSha)
		assertEquals("first-head", persistence.finishedHead)
		assertEquals(current.id, persistence.finishedRequestId)
		assertEquals(emptyList(), client.comparedBases)
	}

	@Test
	fun aheadWithZeroCommitsIsNotAcceptedAsAnExactRange() {
		val persistence = FakeReleasePersistence(previous = listOf(request(tagName = "v1", headSha = "candidate")))
		val client = FakeRangeGitHubClient(
			headSha = "current",
			comparisons = mapOf("candidate" to comparison(status = "ahead", aheadBy = 0)),
		)
		val resolver = DefaultGitHubReleaseRangeResolver(client, persistence, GitHubProperties())

		assertIs<GitHubReleaseRangeResult.NeedsRange>(resolver.resolve(context(), request()))

		assertEquals(null, persistence.savedRange)
		assertEquals("current", persistence.finishedHead)
	}

	private fun context() = GitHubReleaseSourceContext(
		workspaceId = WORKSPACE_ID,
		createdByUserId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
		connectionId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
		bindingId = UUID.fromString("00000000-0000-0000-0000-000000000004"),
		sourceNamespaceId = UUID.fromString("00000000-0000-0000-0000-000000000005"),
		sourceScopeId = SCOPE_ID,
		installationId = 77,
		repositoryId = 44,
		owner = "acme",
		repository = "plot",
		defaultBranch = "main",
	)

	private fun request(
		tagName: String = "v4",
		headSha: String? = null,
		observedHeadSha: String? = null,
	) = GitHubReleaseDraftRequest(
		id = UUID.randomUUID(),
		workspaceId = WORKSPACE_ID,
		sourceScopeId = SCOPE_ID,
		initialDeliveryId = UUID.randomUUID(),
		tagName = tagName,
		baseSha = null,
		headSha = headSha,
		boundaryReason = null,
		status = GitHubReleaseDraftStatus.RESOLVING,
		attemptCount = 1,
		transitionVersion = 3,
		artifactWorkflowRunId = null,
		observationId = null,
		errorCode = null,
		observedHeadSha = observedHeadSha,
	)

	private fun comparison(status: String, aheadBy: Int) = GitHubCompareResult(
		status = status,
		aheadBy = aheadBy,
		commits = emptyList(),
		files = emptyList(),
		filesTruncated = false,
	)

	private class FakeRangeGitHubClient(
		private val headSha: String,
		private val comparisons: Map<String, GitHubCompareResult>,
	) : GitHubClient {
		val comparedBases = mutableListOf<String>()
		val pageCaps = mutableListOf<Int>()

		override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> = error("not used")

		override fun listClosedPullRequests(
			installationId: Long,
			repositoryId: Long,
			owner: String,
			repository: String,
			pageCap: Int,
		): List<GitHubPullRequest> = error("not used")

		override fun resolveTagCommit(
			installationId: Long,
			repositoryId: Long,
			owner: String,
			repository: String,
			tagName: String,
		): String = headSha

		override fun compareCommits(
			installationId: Long,
			repositoryId: Long,
			owner: String,
			repository: String,
			baseSha: String,
			headSha: String,
			pageCap: Int,
		): GitHubCompareResult {
			comparedBases += baseSha
			pageCaps += pageCap
			return comparisons.getValue(baseSha)
		}

		override fun listPullRequestsForCommit(
			installationId: Long,
			repositoryId: Long,
			owner: String,
			repository: String,
			commitSha: String,
		): List<GitHubPullRequest> = error("not used")
	}

	private class FakeReleasePersistence(
		private val previous: List<GitHubReleaseDraftRequest>,
	) : GitHubReleaseRequestStore {
		var savedRange: Triple<String, String, String>? = null
		var finishedHead: String? = null
		var finishedRequestId: UUID? = null

		override fun findPreviousBoundaries(
			workspaceId: UUID,
			sourceScopeId: UUID,
			excludingRequestId: UUID,
		): List<GitHubReleaseDraftRequest> = previous

		override fun saveResolvedRange(
			requestId: UUID,
			transitionVersion: Long,
			baseSha: String,
			headSha: String,
			boundaryReason: String,
		) {
			savedRange = Triple(baseSha, headSha, boundaryReason)
		}

		override fun saveHeadAndFinishNeedsRange(
			requestId: UUID,
			transitionVersion: Long,
			headSha: String,
		) {
			finishedRequestId = requestId
			finishedHead = headSha
		}

		override fun findLatest(sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseDraftRequest? = error("not used")
		override fun releaseScopeExists(sourceScopeId: UUID, workspaceId: UUID): Boolean = error("not used")
		override fun findLatestActivity(sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseActivityRecord? = error("not used")
		override fun findActivity(
			requestId: UUID,
			sourceScopeId: UUID,
			workspaceId: UUID,
		): GitHubReleaseActivityRecord? = error("not used")
		override fun findBoundEvidence(requestId: UUID): GitHubReleaseEvidence? = error("not used")
		override fun findGenerating(limit: Int): List<GitHubReleaseDraftRequest> = error("not used")
		override fun hasGeneratingRequestForAgentRun(workspaceId: UUID, agentRunId: UUID): Boolean = false
		override fun enqueueRelease(
			workspaceId: UUID,
			sourceScopeId: UUID,
			deliveryId: UUID,
			tagName: String,
			observedHeadSha: String?,
		): GitHubReleaseDraftRequest = error("not used")
		override fun linkAgentRun(requestId: UUID, transitionVersion: Long, observationId: UUID, agentRunId: UUID) =
			error("not used")
		override fun linkAgentArtifact(requestId: UUID, transitionVersion: Long, agentRunId: UUID, artifactWorkflowRunId: UUID) =
			error("not used")
		override fun bindEvidence(requestId: UUID, transitionVersion: Long, evidence: GitHubReleaseEvidence) =
			error("not used")
	}

	private companion object {
		val WORKSPACE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
		val SCOPE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
	}
}
