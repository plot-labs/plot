package com.plot.api.github

import com.plot.api.generation.GenerationRunDispatcher
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

interface GitHubReleaseRetryDispatcher {
	fun dispatch(runId: UUID)
}

@Component
class DefaultGitHubReleaseRetryDispatcher(
	private val generationRunDispatcher: GenerationRunDispatcher,
) : GitHubReleaseRetryDispatcher {
	override fun dispatch(runId: UUID) {
		generationRunDispatcher.dispatch()
	}
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
		result.generationRunId?.let { runId ->
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() {
					dispatcher.dispatch(runId)
				}
			})
		}
		return result
	}
}
