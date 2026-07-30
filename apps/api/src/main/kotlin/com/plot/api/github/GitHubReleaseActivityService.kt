package com.plot.api.github

import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GitHubReleaseActivityService(
	private val guard: GitHubGuard,
	private val devContext: DevContext,
	private val persistence: GitHubReleasePersistence,
	private val retryService: GitHubReleaseRetryService,
) {
	@Transactional(readOnly = true)
	fun latest(sourceScopeId: UUID): GitHubReleaseActivityResponse? {
		guard.requireReadAccess()
		val workspaceId = devContext.devWorkspaceId
		requireScope(sourceScopeId, workspaceId)
		return persistence.findLatestActivity(sourceScopeId, workspaceId)?.toResponse()
	}

	@Transactional
	fun retry(sourceScopeId: UUID, requestId: UUID): GitHubReleaseActivityResponse {
		guard.requireReadAccess()
		val workspaceId = devContext.devWorkspaceId
		requireScope(sourceScopeId, workspaceId)
		val activity = persistence.findActivity(requestId, sourceScopeId, workspaceId)
			?: throw notFound()
		if (activity.status != GitHubReleaseDraftStatus.FAILED) {
			throw ApiException(
				HttpStatus.CONFLICT,
				"RELEASE_NOT_RETRYABLE",
				"Only failed release drafts can be retried",
			)
		}
		try {
			retryService.retry(requestId, workspaceId, activity.transitionVersion)
		} catch (_: GitHubReleaseRetryRejectedException) {
			throw notRetryable()
		}
		return persistence.findActivity(requestId, sourceScopeId, workspaceId)?.toResponse()
			?: throw notFound()
	}

	private fun requireScope(sourceScopeId: UUID, workspaceId: UUID) {
		if (!persistence.releaseScopeExists(sourceScopeId, workspaceId)) {
			throw notFound()
		}
	}

	private fun notFound() = ApiException(
		HttpStatus.NOT_FOUND,
		"NOT_FOUND",
		"GitHub release activity was not found",
	)

	private fun notRetryable() = ApiException(
		HttpStatus.CONFLICT,
		"RELEASE_NOT_RETRYABLE",
		"Only failed release drafts can be retried",
	)
}
