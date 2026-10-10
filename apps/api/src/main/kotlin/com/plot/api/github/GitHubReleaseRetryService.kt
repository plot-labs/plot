package com.plot.api.github

import com.plot.api.common.AfterCommit
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GitHubReleaseRetryService(
	private val leasePersistence: GitHubReleaseLeaseStore,
	private val dispatcher: GitHubReleaseDraftDispatcher,
) {
	@Transactional
	fun retry(
		requestId: UUID,
		workspaceId: UUID,
		transitionVersion: Long,
	): GitHubReleaseRetryResult {
		val result = leasePersistence.retry(requestId, workspaceId, transitionVersion)
		AfterCommit.register { dispatcher.dispatch() }
		return result
	}

	@Transactional
	fun selectRange(
		requestId: UUID, workspaceId: UUID, transitionVersion: Long, baseSha: String, headSha: String,
	): GitHubReleaseRetryResult {
		val result = leasePersistence.selectRange(requestId, workspaceId, transitionVersion, baseSha, headSha)
		AfterCommit.register { dispatcher.dispatch() }
		return result
	}
}
