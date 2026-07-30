package com.plot.api.github

import com.plot.api.common.ApiException
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.springframework.core.task.TaskRejectedException
import org.springframework.dao.DataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException

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
		val item = try {
			persistence.claimNext(workerId, clock.instant())
		} catch (exception: RuntimeException) {
			if (exception !is DataAccessException && exception !is TransactionException) throw exception
			null
		} ?: return 0
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
		is ApiException -> exception.error in RETRYABLE_API_ERRORS
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
		minOf(MAX_RETRY_DELAY, Duration.ofSeconds(5L shl attempt.coerceIn(0, MAX_RETRY_SHIFT)))

	private fun isSafeErrorCode(value: String): Boolean =
		value.length in 1..100 && value.all { it.isUpperCase() || it.isDigit() || it == '_' }

	private companion object {
		const val MAX_RETRY_SHIFT = 8
		val MAX_RETRY_DELAY: Duration = Duration.ofMinutes(15)
		val AUTHENTICATION_ERRORS = setOf("GITHUB_ACCESS_DENIED", "GITHUB_NOT_FOUND")
		val RETRYABLE_API_ERRORS = setOf(
			"GITHUB_RATE_LIMITED",
			"GITHUB_NETWORK_ERROR",
			"GITHUB_PROVIDER_UNAVAILABLE",
		)
	}
}
