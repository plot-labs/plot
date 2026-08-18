package com.plot.api.github

import com.plot.api.common.ApiException
import com.plot.api.observability.stopSafely
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.springframework.core.task.TaskRejectedException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Component

@Component
class GitHubReleaseDraftWorker(
	private val leasePersistence: GitHubReleaseLeaseStore,
	private val orchestrator: GitHubReleaseDraftOrchestrator,
	private val properties: GitHubProperties,
	private val leaseFactory: GitHubReleaseLeaseFactory,
	private val clock: Clock = Clock.systemUTC(),
	private val workerId: String = "github-release-${UUID.randomUUID()}",
	private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) {
	fun drain(): Int {
		if (!properties.releaseAutomationEnabled) return 0
		val request = leasePersistence.claimNext(workerId, clock.instant(), properties.releaseWorkerLeaseTimeout)
			?: return 0
		val observation = Observation.start("plot.github.release.attempt", observationRegistry)
			.lowCardinalityKeyValue("plot.operation", "release_draft")
			.lowCardinalityKeyValue("plot.attempt", request.attemptCount.toString())
			.highCardinalityKeyValue("plot.release_request_id", request.id.toString())
			.highCardinalityKeyValue("plot.webhook_delivery_id", request.initialDeliveryId.toString())
			.apply {
				request.agentRunId?.let {
						highCardinalityKeyValue("plot.agent_run_id", it.toString())
				}
			}
		var outcome = "SUCCEEDED"
		try {
			observation.openScope().use {
				leaseFactory.open(request, workerId).use { handle ->
					val status = orchestrator.process(request, handle.lease)
					outcome = if (status == GitHubReleaseDraftStatus.NO_ACTIVITY) "NO_ACTIVITY" else "SUCCEEDED"
				}
			}
		} catch (exception: GitHubReleaseDraftProcessingException) {
			if (exception.cause is GitHubReleaseLeaseLostException) {
				outcome = "LEASE_LOST"
			} else {
				observation.lowCardinalityKeyValue("plot.error_code", safeErrorCode(exception.cause))
				outcome = handleFailure(exception)
			}
		} catch (failure: RuntimeException) {
			observation.lowCardinalityKeyValue("plot.error_code", "RELEASE_PROCESSING_FAILED")
			outcome = "FAILED"
			throw failure
		} finally {
			observation.lowCardinalityKeyValue("plot.outcome", outcome)
			observation.stopSafely()
		}
		return 1
	}

	fun recover(): Int {
		if (!properties.releaseAutomationEnabled) return 0
		return leasePersistence.recoverStaleClaims(clock.instant(), properties.releaseWorkerLeaseTimeout)
	}

	fun reconcile() {
		if (properties.releaseAutomationEnabled) {
			orchestrator.reconcileGenerating(RECONCILE_BATCH_SIZE)
		}
	}

	private fun handleFailure(exception: GitHubReleaseDraftProcessingException): String {
		val errorCode = safeErrorCode(exception.cause)
		val attemptsRemain = exception.attemptCount < properties.releaseWorkerMaxAttempts
		if (attemptsRemain && isRetryable(exception.cause)) {
			leasePersistence.scheduleRetry(
				exception.requestId,
				exception.transitionVersion,
				clock.instant().plus(retryDelay(exception.attemptCount)),
				errorCode,
			)
			return "RETRY_SCHEDULED"
		}
		leasePersistence.finish(
			exception.requestId,
			exception.transitionVersion,
			GitHubReleaseDraftStatus.FAILED,
			errorCode,
		)
		return "FAILED"
	}

	private fun retryDelay(attempt: Int): Duration {
		val boundedShift = attempt.coerceIn(0, MAX_RETRY_SHIFT)
		val seconds = 5L shl boundedShift
		return minOf(MAX_RETRY_DELAY, Duration.ofSeconds(seconds))
	}

	private fun isRetryable(exception: RuntimeException): Boolean = when (exception) {
		is ApiException -> exception.error in RETRYABLE_API_ERRORS
		is TransientDataAccessException, is TaskRejectedException -> true
		else -> false
	}

	private fun safeErrorCode(exception: RuntimeException): String = when (exception) {
		is ApiException -> exception.error.takeIf(::isSafeErrorCode) ?: "RELEASE_PROCESSING_FAILED"
		is GitHubReleasePermanentException -> exception.safeErrorCode
		is TransientDataAccessException -> "RELEASE_STORAGE_TRANSIENT"
		is TaskRejectedException -> "AGENT_ADMISSION_START_TRANSIENT"
		else -> "RELEASE_PROCESSING_FAILED"
	}

	private fun isSafeErrorCode(value: String): Boolean =
		value.length in 1..100 && value.all { it.isUpperCase() || it.isDigit() || it == '_' }

	private companion object {
		const val RECONCILE_BATCH_SIZE = 100
		const val MAX_RETRY_SHIFT = 8
		val MAX_RETRY_DELAY: Duration = Duration.ofMinutes(15)
		val RETRYABLE_API_ERRORS = setOf(
			"GITHUB_RATE_LIMITED",
			"GITHUB_NETWORK_ERROR",
			"GITHUB_PROVIDER_UNAVAILABLE",
		)
	}
}
