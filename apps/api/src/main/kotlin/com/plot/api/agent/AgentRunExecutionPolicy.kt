package com.plot.api.agent

import java.util.UUID

interface AgentRunExecutionPolicy {
	fun isReleaseRun(workspaceId: UUID, agentRunId: UUID): Boolean
	fun releaseRoutineGateFailure(workspaceId: UUID, agentRunId: UUID): String?
}
