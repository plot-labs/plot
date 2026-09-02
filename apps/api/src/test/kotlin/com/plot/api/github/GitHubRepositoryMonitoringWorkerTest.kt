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
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.http.HttpStatus

class GitHubRepositoryMonitoringWorkerTest {
	private val now = Instant.parse("2026-07-31T00:00:00Z")
	private val clock = Clock.fixed(now, ZoneOffset.UTC)
	private val properties = GitHubProperties(enabled = true)
	private val persistence = mock(GitHubRepositoryMonitoringPersistence::class.java)
	private val statusRecorder = mock(GitHubConnectionStatusRecorder::class.java)
	private val client = MonitoringGitHubClient()

	@Test
	fun claimFailurePropagatesForFailureRecovery() {
		doThrow(TransientDataAccessResourceException("database unavailable"))
			.`when`(persistence)
			.claimNext("monitoring-worker", now)

		kotlin.test.assertFailsWith<TransientDataAccessResourceException> {
			worker().drain()
		}
	}

	@Test
	fun transientGitHubErrorSchedulesTheSameRowWithBoundedBackoff() {
		val item = workItem(attemptCount = 1)
		doReturn(item).`when`(persistence).claimNext("monitoring-worker", now)
		client.failure = ApiException(
			HttpStatus.BAD_GATEWAY,
			"GITHUB_PROVIDER_UNAVAILABLE",
			"provider unavailable",
		)

		worker().drain()

		verify(persistence).scheduleRetry(
			item.monitoring.id,
			item.monitoring.transitionVersion,
			"monitoring-worker",
			now.plusSeconds(10),
			"GITHUB_PROVIDER_UNAVAILABLE",
		)
		verifyNoMoreInteractions(*ignoreStubs(persistence))
	}

	@Test
	fun transientStorageFailureDuringCompletionSchedulesTheSameRow() {
		val item = workItem(attemptCount = 1)
		doReturn(item).`when`(persistence).claimNext("monitoring-worker", now)
		doThrow(TransientDataAccessResourceException("database unavailable"))
			.`when`(persistence)
			.complete(
				item.monitoring.id,
				item.monitoring.transitionVersion,
				"monitoring-worker",
				GitHubReleaseConventionAnalysis(
					GitHubReleaseConvention.SEMVER_V,
					null,
					GitHubReleaseSampleSource.RELEASES,
					1,
					false,
				),
				now,
			)
		client.releaseTags = listOf("v1.0.0")

		worker().drain()

		verify(persistence).scheduleRetry(
			item.monitoring.id,
			item.monitoring.transitionVersion,
			"monitoring-worker",
			now.plusSeconds(10),
			"MONITORING_STORAGE_TRANSIENT",
		)
		verifyNoMoreInteractions(*ignoreStubs(persistence))
	}

	@Test
	fun authenticationFailuresUseTheExactConnectionAndWorkspaceScope() {
		listOf("GITHUB_ACCESS_DENIED", "GITHUB_NOT_FOUND").forEach { errorCode ->
			val localPersistence = mock(GitHubRepositoryMonitoringPersistence::class.java)
			val localRecorder = mock(GitHubConnectionStatusRecorder::class.java)
			val localClient = MonitoringGitHubClient().apply {
				failure = ApiException(HttpStatus.BAD_GATEWAY, errorCode, "authentication failed")
			}
			val item = workItem(attemptCount = 1)
			doReturn(item).`when`(localPersistence).claimNext("monitoring-worker", now)
			val worker = worker(localPersistence, localRecorder, localClient)

			worker.drain()

			verify(localRecorder).markNeedsReauthForWorkspace(
				item.connectionId,
				item.monitoring.workspaceId,
			)
			verify(localPersistence).fail(
				item.monitoring.id,
				item.monitoring.transitionVersion,
				"monitoring-worker",
				errorCode,
				now,
			)
			verifyNoMoreInteractions(*ignoreStubs(localPersistence))
		}
	}

	@Test
	fun permanentAnalysisFailureDoesNotRetry() {
		val item = workItem(attemptCount = 1)
		doReturn(item).`when`(persistence).claimNext("monitoring-worker", now)
		client.failure = IllegalStateException("invalid analysis input")

		worker().drain()

		verify(persistence).fail(
			item.monitoring.id,
			item.monitoring.transitionVersion,
			"monitoring-worker",
			"MONITORING_ANALYSIS_FAILED",
			now,
		)
		verifyNoMoreInteractions(*ignoreStubs(persistence))
	}

	@Test
	fun finalAttemptDoesNotScheduleAnotherProviderAttempt() {
		val item = workItem(attemptCount = properties.monitoringAnalysisMaxAttempts)
		doReturn(item).`when`(persistence).claimNext("monitoring-worker", now)
		client.failure = ApiException(
			HttpStatus.BAD_GATEWAY,
			"GITHUB_PROVIDER_UNAVAILABLE",
			"provider unavailable",
		)

		worker().drain()

		assertEquals(1, client.releaseCalls)
		verify(persistence).fail(
			item.monitoring.id,
			item.monitoring.transitionVersion,
			"monitoring-worker",
			"GITHUB_PROVIDER_UNAVAILABLE",
			now,
		)
		verifyNoMoreInteractions(*ignoreStubs(persistence))
	}

	private fun worker(
		persistence: GitHubRepositoryMonitoringPersistence = this.persistence,
		statusRecorder: GitHubConnectionStatusRecorder = this.statusRecorder,
		client: GitHubClient = this.client,
	) = GitHubRepositoryMonitoringWorker(
		persistence = persistence,
		githubClient = client,
		analyzer = GitHubReleaseConventionAnalyzer(),
		properties = properties,
		statusRecorder = statusRecorder,
		clock = clock,
		workerId = "monitoring-worker",
	)

	private fun workItem(attemptCount: Int): GitHubRepositoryMonitoringWorkItem {
		val workspaceId = UUID.randomUUID()
		return GitHubRepositoryMonitoringWorkItem(
			monitoring = GitHubRepositoryMonitoringRecord(
				id = UUID.randomUUID(),
				workspaceId = workspaceId,
				sourceScopeId = UUID.randomUUID(),
				monitoringStatus = GitHubRepositoryMonitoringStatus.ACTIVE,
				analysisStatus = GitHubRepositoryAnalysisStatus.ANALYZING,
				releaseConvention = null,
				tagPrefix = null,
				sampleSource = null,
				sampleSize = 0,
				sampleTruncated = false,
				attemptCount = attemptCount,
				transitionVersion = 9,
				claimedBy = "monitoring-worker",
				claimedAt = now,
				nextAttemptAt = null,
				lastErrorCode = null,
				analyzedAt = null,
				createdAt = now,
				updatedAt = now,
			),
			connectionId = UUID.randomUUID(),
			installationId = 77,
			repositoryId = 44,
			owner = "acme",
			repository = "plot",
		)
	}
}

private class MonitoringGitHubClient : GitHubClient {
	var failure: RuntimeException? = null
	var releaseTags: List<String> = emptyList()
	var releaseCalls = 0

	override fun listPublishedReleaseTags(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		limit: Int,
	): GitHubTagPage {
		releaseCalls++
		failure?.let { throw it }
		return GitHubTagPage(releaseTags, false)
	}

	override fun listRepositoryTags(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		limit: Int,
	): GitHubTagPage = GitHubTagPage(emptyList(), false)

	override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> =
		error("not used")

	override fun verifyRepositoryAccess(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
	): GitHubRepository = error("not used")

	override fun listClosedPullRequests(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		pageCap: Int,
	): List<GitHubPullRequest> = error("not used")
}
