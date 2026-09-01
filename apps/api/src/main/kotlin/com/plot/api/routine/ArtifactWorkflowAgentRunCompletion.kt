package com.plot.api.routine

import com.plot.api.github.GitHubReleaseReconciliationTrigger
import java.time.Clock
import java.util.UUID
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

fun interface ArtifactWorkflowAgentRunCompletionHandler {
	fun onTerminal(workspaceId: UUID, workflowRunId: UUID)
}

@Component
class DefaultArtifactWorkflowAgentRunCompletion(
	private val executionPersistence: AgentRunExecutionPersistence,
	private val agentRunDispatcher: AgentRunDispatcher,
	@Lazy private val releaseReconciliation: GitHubReleaseReconciliationTrigger,
	private val properties: RoutineAgentProperties,
	private val clock: Clock = Clock.systemUTC(),
) : ArtifactWorkflowAgentRunCompletionHandler {
	override fun onTerminal(workspaceId: UUID, workflowRunId: UUID) {
		if (!properties.workersEnabled) return
		if (executionPersistence.completeWaitingArtifactHandoff(workspaceId, workflowRunId, clock.instant())) {
			agentRunDispatcher.dispatch()
		}
		notifyReleaseReconciliationAfterCommit(workspaceId, workflowRunId)
	}

	private fun notifyReleaseReconciliationAfterCommit(workspaceId: UUID, workflowRunId: UUID) {
		if (
			TransactionSynchronizationManager.isSynchronizationActive() &&
				TransactionSynchronizationManager.isActualTransactionActive()
		) {
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() {
					releaseReconciliation.afterArtifactWorkflowTerminal(workspaceId, workflowRunId)
				}
			})
		} else {
			releaseReconciliation.afterArtifactWorkflowTerminal(workspaceId, workflowRunId)
		}
	}
}
