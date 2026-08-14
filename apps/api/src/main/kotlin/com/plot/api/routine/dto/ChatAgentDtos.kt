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
	val instruction: String,
	val status: AgentRunStatus,
	val failureCode: String?,
	val artifactId: UUID?,
	val artifact: ChatAgentArtifactSummaryResponse?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

data class ChatAgentArtifactSummaryResponse(
	val id: UUID,
	val status: String,
	val title: String?,
	val updatedAt: Instant,
)

fun AgentRunRecord.toChatResponse(artifact: ChatAgentArtifactSummaryResponse? = null) = ChatAgentRunResponse(
	id = id,
	chatId = requireNotNull(workSessionId) { "Chat Agent run is missing its Chat" },
	instruction = instructionSnapshot,
	status = status,
	failureCode = failureCode,
	artifactId = artifact?.id,
	artifact = artifact,
	createdAt = createdAt,
	updatedAt = updatedAt,
)
