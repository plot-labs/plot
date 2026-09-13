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
	private val sql: com.plot.api.persistence.JooqSqlExecutor,
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
		requireOwner()
		val workspaceId = devContext.devWorkspaceId
		requireScope(sourceScopeId, workspaceId)
		val activity = requestPersistence.findActivity(requestId, sourceScopeId, workspaceId)
			?: throw notFound()
		val isChatRun = activity.agentRunId?.let { runId ->
			sql.query(
				"select 1 from agent_runs where id = ? and origin = 'CHAT'",
				{ _, _ -> true },
				runId,
			).firstOrNull() ?: false
		} ?: false
		if (activity.status != GitHubReleaseDraftStatus.FAILED || isChatRun) {
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

	@Transactional(readOnly = true)
	fun get(sourceScopeId: UUID, requestId: UUID): GitHubReleaseActivityResponse {
		guard.requireReadAccess()
		val workspaceId = devContext.devWorkspaceId
		requireScope(sourceScopeId, workspaceId)
		return requestPersistence.findActivity(requestId, sourceScopeId, workspaceId)?.toResponse() ?: throw notFound()
	}

	@Transactional
	fun selectRange(sourceScopeId: UUID, requestId: UUID, range: GitHubReleaseRangeRequest): GitHubReleaseActivityResponse {
		guard.requireReadAccess()
		requireOwner()
		val workspaceId = devContext.devWorkspaceId
		requireScope(sourceScopeId, workspaceId)
		val activity = requestPersistence.findActivity(requestId, sourceScopeId, workspaceId) ?: throw notFound()
		try {
			retryService.selectRange(requestId, workspaceId, activity.transitionVersion, range.baseSha, range.headSha)
		} catch (_: GitHubReleaseRetryRejectedException) {
			throw ApiException(HttpStatus.CONFLICT, "RELEASE_RANGE_NOT_SELECTABLE", "Choose a range only for an unresolved release with no bound evidence; its tag head cannot change")
		}
		return requestPersistence.findActivity(requestId, sourceScopeId, workspaceId)?.toResponse() ?: throw notFound()
	}

	private fun requireOwner() {
		val actor = actorResolver?.current()
		if (actor != null && actorResolver.requireWorkspace().role != "OWNER") {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Workspace owner access is required")
		}
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
