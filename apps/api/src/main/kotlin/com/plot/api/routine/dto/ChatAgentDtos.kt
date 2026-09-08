package com.plot.api.routine.dto

import com.plot.api.content.ConfirmedFact
import com.plot.api.content.ContentBrief
import com.plot.api.content.ContentBriefDestination
import com.plot.api.content.ContentType
import com.plot.api.common.ApiException
import com.plot.api.routine.AgentRunRecord
import com.plot.api.routine.AgentRunStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus

data class CreateChatAgentRunRequest(
	@field:NotBlank @field:Size(max = 2_000) val instruction: String,
	val workSessionId: UUID? = null,
	@field:Size(max = 20) val writingBlockIds: List<UUID> = emptyList(),
	val contentType: ContentType = ContentType.CHANGELOG,
	val contentProfileRevisionId: UUID? = null,
	@field:Valid val brief: ContentBriefRequest? = null,
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
	val contentType: ContentType,
	val contentProfileRevisionId: UUID?,
	val brief: ContentBrief?,
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
	val contentType: ContentType,
	val updatedAt: Instant,
)

fun AgentRunRecord.toChatResponse(
	artifact: ChatAgentArtifactSummaryResponse? = null,
	brief: ContentBrief? = null,
) = ChatAgentRunResponse(
	id = id,
	chatId = requireNotNull(workSessionId) { "Chat Agent run is missing its Chat" },
	instruction = instructionSnapshot,
	contentType = contentType,
	contentProfileRevisionId = contentProfileRevisionId,
	brief = brief,
	status = status,
	failureCode = failureCode,
	artifactId = artifact?.id,
	artifact = artifact,
	createdAt = createdAt,
	updatedAt = updatedAt,
)
