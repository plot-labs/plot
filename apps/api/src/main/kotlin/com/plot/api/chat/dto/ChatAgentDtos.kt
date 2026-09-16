package com.plot.api.chat.dto

import com.plot.api.common.ApiException
import com.plot.api.content.ConfirmedFact
import com.plot.api.content.ContentBrief
import com.plot.api.content.ContentBriefDestination
import com.plot.api.agent.AgentRunRecord
import com.plot.api.agent.AgentRunStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus

data class CreateChatAgentRunRequest(
	@field:NotBlank @field:Size(max = 2_000) val instruction: String,
	@field:Size(max = 4) val skillIds: List<UUID> = emptyList(),
	val workSessionId: UUID? = null,
	@field:Size(max = 20) val writingBlockIds: List<UUID> = emptyList(),
)

data class ContentBriefRequest(
	@field:Size(max = 2_000) val purpose: String? = null,
	@field:Size(max = 2_000) val audience: String? = null,
	@field:Size(max = 2_000) val availability: String? = null,
	@field:Size(max = 2_000) val pricing: String? = null,
	@field:Size(max = 2_000) val userAction: String? = null,
	@field:Size(max = 20) @field:Valid val confirmedFacts: List<@Valid ConfirmedFactRequest> = emptyList(),
	@field:Size(max = 20) @field:Valid val destinations: List<@Valid CtaDestinationRequest> = emptyList(),
) {
	fun toDomain(): ContentBrief = ContentBrief(
		purpose = purpose?.trim()?.ifBlank { null },
		audience = audience?.trim()?.ifBlank { null },
		availability = availability?.trim()?.ifBlank { null },
		pricing = pricing?.trim()?.ifBlank { null },
		userAction = userAction?.trim()?.ifBlank { null },
		confirmedFacts = confirmedFacts.mapNotNull { fact ->
			val body = fact.body.trim()
			if (body.isEmpty()) null
			else ConfirmedFact(body = body, kind = fact.kind.trim().ifBlank { "AVAILABILITY" })
		},
		destinations = destinations.map { destination ->
			val id = destination.id ?: throw ApiException(
				HttpStatus.BAD_REQUEST,
				"INVALID_CTA_DESTINATION",
				"CTA destination id is required",
			)
			try {
				ContentBriefDestination(id, destination.label.trim(), destination.url.trim())
			} catch (error: IllegalArgumentException) {
				throw ApiException(
					HttpStatus.BAD_REQUEST,
					"INVALID_CTA_DESTINATION",
					error.message ?: "CTA destination is invalid",
				)
			}
		}.also { normalized ->
			if (normalized.map { it.id }.distinct().size != normalized.size) {
				throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_CTA_DESTINATION", "CTA destination ids must be unique")
			}
		},
	)
}

data class ConfirmedFactRequest(
	@field:NotBlank @field:Size(max = 2_000) val body: String,
	@field:Size(max = 64) val kind: String = "AVAILABILITY",
)

data class CtaDestinationRequest(
	val id: UUID? = null,
	@field:NotBlank @field:Size(max = 200) val label: String = "",
	@field:NotBlank @field:Size(max = 2_000) val url: String = "",
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
