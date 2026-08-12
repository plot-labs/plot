package com.plot.api.routine.dto

import com.plot.api.routine.AgentRunRecord
import com.plot.api.routine.AgentRunStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateChatAgentRunRequest(
	@field:NotBlank @field:Size(max = 2_000) val instruction: String,
	val workSessionId: UUID? = null,
	@field:Size(max = 20) val writingBlockIds: List<UUID> = emptyList(),
)

data class ChatAgentRunResponse(
	val id: UUID,
	val chatId: UUID,
	val status: AgentRunStatus,
	val failureCode: String?,
	val generationRunId: UUID?,
	val artifactId: UUID?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

fun AgentRunRecord.toChatResponse(
	generationRunId: UUID? = null,
	artifactId: UUID? = null,
) = ChatAgentRunResponse(
	id = id,
	chatId = requireNotNull(workSessionId) { "Chat Agent run is missing its Chat" },
	status = status,
	failureCode = failureCode,
	generationRunId = generationRunId,
	artifactId = artifactId,
	createdAt = createdAt,
	updatedAt = updatedAt,
)
