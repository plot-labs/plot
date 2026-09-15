package com.plot.api.agent

import java.time.Instant
import java.util.UUID

/** Called synchronously within the execution's transaction; failures must propagate. */
interface AgentRunCompletionProjection {
	fun deactivateResponse(workspaceId: UUID, agentRunId: UUID)
	fun projectTerminal(run: AgentRunRecord, status: AgentRunStatus, errorCode: String?, now: Instant)
	fun commitSuccessfulInput(run: AgentRunRecord, now: Instant)
}

class AgentRunStateException(message: String) : IllegalStateException(message)
