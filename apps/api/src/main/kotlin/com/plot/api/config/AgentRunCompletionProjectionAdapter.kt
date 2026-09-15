package com.plot.api.config

import com.plot.api.agent.AgentRunCompletionProjection
import com.plot.api.agent.AgentRunRecord
import com.plot.api.agent.AgentRunStatus
import com.plot.api.chat.ChatPersistence
import com.plot.api.routine.RoutineAgentRunProjection
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class AgentRunCompletionProjectionAdapter(
	private val chat: ChatPersistence,
	private val routine: RoutineAgentRunProjection,
) : AgentRunCompletionProjection {
	override fun deactivateResponse(workspaceId: UUID, agentRunId: UUID) = chat.deactivateResponse(workspaceId, agentRunId)
	override fun projectTerminal(run: AgentRunRecord, status: AgentRunStatus, errorCode: String?, now: Instant) =
		routine.projectTerminal(run, status, errorCode, now)
	override fun commitSuccessfulInput(run: AgentRunRecord, now: Instant) = routine.commitSuccessfulInput(run, now)
}
