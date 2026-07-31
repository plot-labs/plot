package com.plot.api.github

import com.plot.api.common.ApiException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.ignoreStubs
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.http.HttpStatus

class GitHubRepositoryAccessCheckWorkerTest {
	private val now = Instant.parse("2026-07-31T00:00:00Z")
	private val clock = Clock.fixed(now, ZoneOffset.UTC)
	private val properties = GitHubProperties(enabled = true)
	private val persistence = mock(GitHubRepositoryAccessCheckPersistence::class.java)
	private val client = AccessCheckGitHubClient()

	@Test
	fun successfulVerificationRestoresTheSameScope() {
		val item = workItem(attemptCount = 1)
		doReturn(item).`when`(persistence).claimNext("access-check-worker", now)
		client.repositories = listOf(repository())

		worker().drain()

		verify(persistence).completeVerified(item, repository(), now)
		verifyNoMoreInteractions(*ignoreStubs(persistence))
	}

	@Test
	fun transientProviderFailureUsesFiveSecondBackoff() {
		val item = workItem(attemptCount = 1)
		doReturn(item).`when`(persistence).claimNext("access-check-worker", now)
		client.failure = ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_PROVIDER_UNAVAILABLE", "provider unavailable")

		worker().drain()

		verify(persistence).scheduleRetry(item, now.plusSeconds(5), "GITHUB_PROVIDER_UNAVAILABLE", now)
		verifyNoMoreInteractions(*ignoreStubs(persistence))
	}

	@Test
	fun accessDeniedIsTerminalWithoutAnotherProviderAttempt() {
		val item = workItem(attemptCount = 1)
		doReturn(item).`when`(persistence).claimNext("access-check-worker", now)
		client.failure = ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_ACCESS_DENIED", "access denied")

		worker().drain()

		verify(persistence).fail(item, "GITHUB_ACCESS_DENIED", now)
		verifyNoMoreInteractions(*ignoreStubs(persistence))
	}

	@Test
	fun thirdTransientAttemptIsTerminal() {
		val item = workItem(attemptCount = 3)
		doReturn(item).`when`(persistence).claimNext("access-check-worker", now)
		client.failure = ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_NETWORK_ERROR", "network unavailable")

		worker().drain()

		verify(persistence).fail(item, "GITHUB_NETWORK_ERROR", now)
		verifyNoMoreInteractions(*ignoreStubs(persistence))
	}

	@Test
	fun lostClaimDoesNotScheduleOrFailAReplacementCheck() {
		val item = workItem(attemptCount = 1)
		doReturn(item).`when`(persistence).claimNext("access-check-worker", now)
		client.repositories = listOf(repository())
		doThrow(GitHubAccessCheckClaimLostException())
			.`when`(persistence)
			.completeVerified(item, repository(), now)

		worker().drain()

		verifyNoMoreInteractions(*ignoreStubs(persistence))
	}

	private fun worker(
		persistence: GitHubRepositoryAccessCheckPersistence = this.persistence,
		client: GitHubClient = this.client,
	) = GitHubRepositoryAccessCheckWorker(
		persistence = persistence,
		githubClient = client,
		properties = properties,
		clock = clock,
		workerId = "access-check-worker",
	)

	private fun workItem(attemptCount: Int) = GitHubRepositoryAccessCheckWorkItem(
		check = GitHubRepositoryAccessCheckRecord(
			id = UUID.randomUUID(),
			workspaceId = UUID.randomUUID(),
			connectionId = UUID.randomUUID(),
			sourceScopeId = UUID.randomUUID(),
			trigger = GitHubAccessCheckTrigger.RETRY,
			status = GitHubAccessCheckStatus.CHECKING,
			attemptCount = attemptCount,
			transitionVersion = 4,
			claimedBy = "access-check-worker",
			claimedAt = now,
			nextAttemptAt = null,
			errorCode = null,
			verifiedAt = null,
			createdAt = now,
			updatedAt = now,
		),
		installationId = 77,
		repositoryId = 44,
		owner = "acme",
		repository = "plot",
	)

	private fun repository() = GitHubRepository(
		id = 44,
		owner = "acme",
		name = "plot",
		url = "https://github.com/acme/plot",
		defaultBranch = "main",
	)
}

private class AccessCheckGitHubClient : GitHubClient {
	var repositories: List<GitHubRepository> = emptyList()
	var failure: RuntimeException? = null

	override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> {
		failure?.let { throw it }
		return repositories
	}

	override fun verifyRepositoryAccess(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
	): GitHubRepository {
		failure?.let { throw it }
		return this.repositories.first { it.id == repositoryId }
	}

	override fun listClosedPullRequests(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		pageCap: Int,
	): List<GitHubPullRequest> = error("not used")
}
