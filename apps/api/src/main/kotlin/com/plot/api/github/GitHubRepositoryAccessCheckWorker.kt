package com.plot.api.github

import com.plot.api.common.ApiException
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.springframework.dao.DataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException

@Component
class GitHubRepositoryAccessCheckWorker(
	private val persistence: GitHubRepositoryAccessCheckPersistence,
	private val githubClient: GitHubClient,
	private val properties: GitHubProperties,
	private val clock: Clock = Clock.systemUTC(),
	private val workerId: String = "github-access-check-${UUID.randomUUID()}",
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
			if (item.installationId <= 0L || item.repositoryId <= 0L || item.owner.isBlank() || item.repository.isBlank()) {
				throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub repository identity is invalid")
			}
			val granted = githubClient.listInstallationRepositories(item.installationId)
				.firstOrNull { it.id == item.repositoryId }
				?: throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_NOT_FOUND", "GitHub repository is not granted")
			val verified = githubClient.verifyRepositoryAccess(
				item.installationId,
				item.repositoryId,
				granted.owner,
				granted.name,
			)
			if (verified.id != item.repositoryId) {
				throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub repository identity did not match")
			}
			persistence.completeVerified(item, verified, clock.instant())
		} catch (exception: RuntimeException) {
			handleFailure(item, exception)
		}
		return 1
	}

	fun recover(): Int {
		if (!properties.enabled) return 0
		return persistence.recoverStaleClaims(
			clock.instant(),
			properties.accessCheckLeaseTimeout,
			properties.accessCheckMaxAttempts,
		)
	}

	private fun handleFailure(item: GitHubRepositoryAccessCheckWorkItem, exception: RuntimeException) {
		if (exception is GitHubAccessCheckClaimLostException) return
		val errorCode = safeErrorCode(exception)
		val attemptsRemain = item.check.attemptCount < properties.accessCheckMaxAttempts
		try {
			if (attemptsRemain && isRetryable(exception)) {
				persistence.scheduleRetry(
					item,
					clock.instant().plus(retryDelay(item.check.attemptCount)),
					errorCode,
					clock.instant(),
				)
			} else {
				persistence.fail(item, errorCode, clock.instant())
			}
		} catch (_: GitHubAccessCheckClaimLostException) {
			// A lifecycle event or another worker already advanced the claim.
		}
	}

	private fun retryDelay(attempt: Int): Duration {
		val shift = (attempt - 1).coerceIn(0, MAX_RETRY_SHIFT)
		return minOf(MAX_RETRY_DELAY, Duration.ofSeconds(5L shl shift))
	}

	private fun isRetryable(exception: RuntimeException): Boolean = when (exception) {
		is ApiException -> exception.error in RETRYABLE_API_ERRORS
		is TransientDataAccessException -> true
		else -> false
	}

	private fun safeErrorCode(exception: RuntimeException): String = when (exception) {
		is ApiException -> exception.error.takeIf { it in SAFE_ERROR_CODES } ?: "GITHUB_ACCESS_CHECK_FAILED"
		is TransientDataAccessException -> "ACCESS_CHECK_STORAGE_TRANSIENT"
		else -> "GITHUB_ACCESS_CHECK_FAILED"
	}

	private companion object {
		const val MAX_RETRY_SHIFT = 8
		val MAX_RETRY_DELAY: Duration = Duration.ofMinutes(15)
		val RETRYABLE_API_ERRORS = setOf(
			"GITHUB_RATE_LIMITED",
			"GITHUB_NETWORK_ERROR",
			"GITHUB_PROVIDER_UNAVAILABLE",
		)
		val SAFE_ERROR_CODES = RETRYABLE_API_ERRORS + setOf(
			"GITHUB_ACCESS_DENIED",
			"GITHUB_NOT_FOUND",
			"GITHUB_INVALID_RESPONSE",
			"GITHUB_NOT_CONFIGURED",
			"GITHUB_KEY_INVALID",
		)
	}
}
