package com.plot.api.github

import com.plot.api.common.ApiException
import java.time.Clock
import com.plot.api.common.WorkerBackoff
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.core.task.TaskRejectedException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Component

@Component
class GitHubRepositoryMonitoringWorker(
	private val persistence: GitHubRepositoryMonitoringPersistence,
	private val githubClient: GitHubClient,
	private val analyzer: GitHubReleaseConventionAnalyzer,
	private val properties: GitHubProperties,
	private val statusRecorder: GitHubConnectionStatusRecorder,
	private val clock: Clock = Clock.systemUTC(),
	private val workerId: String = "github-monitoring-${UUID.randomUUID()}",
) {
	fun drain(): Int {
		if (!properties.enabled) return 0
		val item = persistence.claimNext(workerId, clock.instant()) ?: return 0
		try {
			val releases = githubClient.listPublishedReleaseTags(
				item.installationId,
				item.repositoryId,
				item.owner,
				item.repository,
				properties.monitoringAnalysisSampleLimit,
			)
			val sample = if (releases.tags.isNotEmpty()) {
				GitHubReleaseTagSample(releases.tags, GitHubReleaseSampleSource.RELEASES, releases.truncated)
			} else {
				val tags = githubClient.listRepositoryTags(
					item.installationId,
					item.repositoryId,
					item.owner,
					item.repository,
					properties.monitoringAnalysisSampleLimit,
				)
				GitHubReleaseTagSample(
					tags.tags,
					GitHubReleaseSampleSource.TAGS.takeIf { tags.tags.isNotEmpty() },
					tags.truncated,
				)
			}
			persistence.complete(
				item.monitoring.id,
				item.monitoring.transitionVersion,
				workerId,
				analyzer.analyze(sample),
				clock.instant(),
			)
		} catch (exception: RuntimeException) {
			handleFailure(item, exception)
		}
		return 1
	}

	/** Earliest persisted retry that is not yet due, used to re-arm the dispatcher. */
	fun nextRetryAt(): Instant? {
		if (!properties.enabled) return null
		return persistence.earliestNextAttemptAt(clock.instant())
	}

	fun recover(): Int {
		if (!properties.enabled) return 0
		return persistence.recoverStaleClaims(
			clock.instant(),
			properties.monitoringAnalysisLeaseTimeout,
			properties.monitoringAnalysisMaxAttempts,
		)
	}

	private fun handleFailure(item: GitHubRepositoryMonitoringWorkItem, exception: RuntimeException) {
		val errorCode = safeErrorCode(exception)
		if (errorCode in AUTHENTICATION_ERRORS) {
			statusRecorder.markNeedsReauthForWorkspace(item.connectionId, item.monitoring.workspaceId)
		}
		val attemptsRemain = item.monitoring.attemptCount < properties.monitoringAnalysisMaxAttempts
		if (attemptsRemain && isRetryable(exception)) {
			persistence.scheduleRetry(
				item.monitoring.id,
				item.monitoring.transitionVersion,
				workerId,
				clock.instant().plus(retryDelay(item.monitoring.attemptCount)),
				errorCode,
			)
		} else {
			persistence.fail(
				item.monitoring.id,
				item.monitoring.transitionVersion,
				workerId,
				errorCode,
				clock.instant(),
			)
		}
	}

	private fun isRetryable(exception: RuntimeException): Boolean = when (exception) {
		is ApiException -> GitHubTransientErrors.isTransient(exception)
		is TransientDataAccessException, is TaskRejectedException -> true
		else -> false
	}

	private fun safeErrorCode(exception: RuntimeException): String = when (exception) {
		is ApiException -> exception.error.takeIf(::isSafeErrorCode) ?: "MONITORING_ANALYSIS_FAILED"
		is TransientDataAccessException -> "MONITORING_STORAGE_TRANSIENT"
		is TaskRejectedException -> "MONITORING_EXECUTOR_TRANSIENT"
		else -> "MONITORING_ANALYSIS_FAILED"
	}

	private fun retryDelay(attempt: Int): Duration =
		WorkerBackoff.delay(RETRY_INITIAL_DELAY, attempt)

	private fun isSafeErrorCode(value: String): Boolean =
		value.length in 1..100 && value.all { it.isUpperCase() || it.isDigit() || it == '_' }

	private companion object {
		val RETRY_INITIAL_DELAY: Duration = Duration.ofSeconds(5)
		val AUTHENTICATION_ERRORS = setOf("GITHUB_ACCESS_DENIED", "GITHUB_NOT_FOUND")
	}
}
