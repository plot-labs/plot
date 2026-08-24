package com.plot.api.github

import com.plot.api.common.ApiException
import com.plot.api.auth.RequestActorResolver
import com.plot.api.dev.DevContext
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GitHubReleaseActivityService(
	private val guard: GitHubGuard,
	private val devContext: DevContext,
	private val requestPersistence: GitHubReleaseRequestStore,
	private val retryService: GitHubReleaseRetryService,
	private val actorResolver: RequestActorResolver? = null,
) {
	@Transactional(readOnly = true)
	fun latest(sourceScopeId: UUID): GitHubReleaseActivityResponse? {
		guard.requireReadAccess()
		val workspaceId = devContext.devWorkspaceId
		requireScope(sourceScopeId, workspaceId)
		return requestPersistence.findLatestActivity(sourceScopeId, workspaceId)?.toResponse()
	}

	@Transactional
	fun retry(sourceScopeId: UUID, requestId: UUID): GitHubReleaseActivityResponse {
		guard.requireReadAccess()
		// Retries re-drive release automation and external quota, so they stay
		// an owner-level action like connections and monitoring.
		val actor = actorResolver?.current()
		if (actor != null && actorResolver.requireWorkspace().role != "OWNER") {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Workspace owner access is required")
		}
		val workspaceId = devContext.devWorkspaceId
		requireScope(sourceScopeId, workspaceId)
		val activity = requestPersistence.findActivity(requestId, sourceScopeId, workspaceId)
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
		return requestPersistence.findActivity(requestId, sourceScopeId, workspaceId)?.toResponse()
			?: throw notFound()
	}

	private fun requireScope(sourceScopeId: UUID, workspaceId: UUID) {
		if (!requestPersistence.releaseScopeExists(sourceScopeId, workspaceId)) {
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
