package com.plot.api.routine

import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Component

fun interface ArtifactWorkflowAgentRunCompletionHandler {
	fun onTerminal(workspaceId: UUID, workflowRunId: UUID)
}

@Component
class DefaultArtifactWorkflowAgentRunCompletion(
	private val executionPersistence: AgentRunExecutionPersistence,
	private val agentRunDispatcher: AgentRunDispatcher,
	private val properties: RoutineAgentProperties,
	private val clock: Clock = Clock.systemUTC(),
) : ArtifactWorkflowAgentRunCompletionHandler {
	override fun onTerminal(workspaceId: UUID, workflowRunId: UUID) {
		if (!properties.workersEnabled) return
		if (executionPersistence.completeWaitingArtifactHandoff(workspaceId, workflowRunId, clock.instant())) {
			agentRunDispatcher.dispatch()
		}
	}
}
