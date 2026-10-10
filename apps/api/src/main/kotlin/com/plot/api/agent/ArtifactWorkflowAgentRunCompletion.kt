package com.plot.api.agent

import com.plot.api.common.AfterCommit
import com.plot.api.github.GitHubReleaseReconciliationTrigger
import java.time.Clock
import java.util.UUID
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

fun interface ArtifactWorkflowAgentRunCompletionHandler {
	fun onTerminal(workspaceId: UUID, workflowRunId: UUID)
}

@Component
class DefaultArtifactWorkflowAgentRunCompletion(
	private val recoveryPersistence: AgentRunRecoveryPersistence,
	private val agentRunDispatcher: AgentRunDispatcher,
	@Lazy private val releaseReconciliation: GitHubReleaseReconciliationTrigger,
	private val properties: AgentProperties,
	private val clock: Clock = Clock.systemUTC(),
) : ArtifactWorkflowAgentRunCompletionHandler {
	override fun onTerminal(workspaceId: UUID, workflowRunId: UUID) {
		if (!properties.workersEnabled) return
		try {
			if (recoveryPersistence.completeWaitingArtifactHandoff(workspaceId, workflowRunId, clock.instant())) {
				agentRunDispatcher.dispatch()
			}
		} catch (_: RuntimeException) {
			agentRunDispatcher.dispatch()
		}
		AfterCommit.runOrNow { releaseReconciliation.afterArtifactWorkflowTerminal(workspaceId, workflowRunId) }
	}
}
