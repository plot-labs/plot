package com.plot.api.chat.dto

import com.plot.api.chat.ChatReasoningEfforts
import com.plot.api.agent.AgentRunRecord
import com.plot.api.agent.AgentRunStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateChatAgentRunRequest(
	@field:NotBlank @field:Size(max = 2_000) val instruction: String,
	@field:Size(max = 4) val skillIds: List<UUID> = emptyList(),
	val workSessionId: UUID? = null,
	@field:Size(max = 20) val writingBlockIds: List<UUID> = emptyList(),
	@field:NotBlank @field:Size(max = 100) val model: String = "auto",
	@field:Size(max = 16) val reasoningEffort: String? = ChatReasoningEfforts.DEFAULT,
)

data class ChatAgentRunResponse(
	val id: UUID,
	val chatId: UUID,
	val instruction: String,
	val skills: List<com.plot.api.skill.SkillSnapshot> = emptyList(),
	val status: AgentRunStatus,
	val failureCode: String?,
	val responseText: String?,
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

fun AgentRunRecord.toChatResponse(
	artifact: ChatAgentArtifactSummaryResponse? = null,
	responseText: String? = null,
) = ChatAgentRunResponse(
	id = id,
	chatId = requireNotNull(workSessionId) { "Chat Agent run is missing its Chat" },
	instruction = instructionSnapshot,
	skills = com.plot.api.skill.FrozenSkills.read(skillsSnapshotJson),
	status = status,
	failureCode = failureCode,
	responseText = responseText,
	artifactId = artifact?.id,
	artifact = artifact,
	createdAt = createdAt,
	updatedAt = updatedAt,
)

data class RetryEligibilityDto(
	val eligible: Boolean,
	val reason: String? = null,
)

data class ChatResponseSourceDto(
	val id: UUID,
	val displayName: String,
	val role: String,
)

data class ChatResponseCitationDto(
	val id: UUID,
	val title: String?,
	val excerpt: String,
	val url: String?,
)

data class ChatResponseVersionDto(
	val id: UUID,
	val turnId: UUID,
	val versionIndex: Int,
	val agentRunId: UUID,
	val lineageParentVersionId: UUID?,
	val status: AgentRunStatus,
	val failureCode: String?,
	val instruction: String,
	val responseText: String?,
	val artifactId: UUID?,
	val artifact: ChatAgentArtifactSummaryResponse?,
	val retryEligibility: RetryEligibilityDto,
	val sources: List<ChatResponseSourceDto> = emptyList(),
	val citations: List<ChatResponseCitationDto> = emptyList(),
	val createdAt: Instant,
	val updatedAt: Instant,
)

data class ChatTurnDto(
	val id: UUID,
	val workSessionId: UUID,
	val turnIndex: Int,
	val userMessage: String,
	val versions: List<ChatResponseVersionDto>,
	val selectedVersionId: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
)
