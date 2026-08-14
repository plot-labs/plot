package com.plot.api.github

import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

interface GitHubReleaseRetryDispatcher {
	fun dispatch()
}

@Component
class DefaultGitHubReleaseRetryDispatcher(
	private val releaseDraftDispatcher: GitHubReleaseDraftDispatcher,
) : GitHubReleaseRetryDispatcher {
	override fun dispatch() = releaseDraftDispatcher.dispatch()
}

@Service
class GitHubReleaseRetryService(
	private val persistence: GitHubReleasePersistence,
	private val dispatcher: GitHubReleaseRetryDispatcher,
) {
	@Transactional
	fun retry(
		requestId: UUID,
		workspaceId: UUID,
		transitionVersion: Long,
	): GitHubReleaseRetryResult {
		val result = persistence.retry(requestId, workspaceId, transitionVersion)
		TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
			override fun afterCommit() {
				dispatcher.dispatch()
			}
		})
		return result
	}
}
