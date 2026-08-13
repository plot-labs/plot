package com.plot.api.github

import com.plot.api.artifact.workflow.ArtifactWorkflowRunDispatcher
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
	private val artifactWorkflowRunDispatcher: ArtifactWorkflowRunDispatcher,
) : GitHubReleaseRetryDispatcher {
	override fun dispatch(runId: UUID) {
		artifactWorkflowRunDispatcher.dispatch()
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
		result.artifactWorkflowRunId?.let { runId ->
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() {
					dispatcher.dispatch(runId)
				}
			})
		}
		return result
	}
}
