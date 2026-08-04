package com.plot.api.contentpack.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import jakarta.validation.Valid
import tools.jackson.databind.JsonNode
import java.util.UUID

data class ContentStatementInput(
	val id: UUID?,
	@field:NotNull @field:Min(0) val orderIndex: Int?,
	@field:NotBlank @field:Size(max = 10_000) val body: String?,
)

data class SaveContentVariantRequest(
	@field:NotNull val expectedRevisionNumber: Int?,
	@field:NotNull val lexicalContent: JsonNode?,
	@field:NotNull @field:Size(max = 1_000) @field:Valid val statements: List<@Valid ContentStatementInput>?,
)

/**
 * Kept as a compatibility boundary for older clients. New editor saves use
 * SaveContentVariantRequest and the whole-artifact endpoint.
 */
data class EditSentenceRequest(
	@field:NotNull val expectedRevisionNumber: Int?,
	@field:NotBlank @field:Size(max = 10_000) val body: String?,
)

enum class ExportDisposition { COPY, DOWNLOAD }

data class ExportContentVariantRequest(
	@field:NotNull val expectedRevisionNumber: Int?,
	val includeSources: Boolean = false,
	val acknowledgeUnresolved: Boolean = false,
	@field:Size(max = 1_000) val acknowledgedWarningKeys: List<String> = emptyList(),
	/** @deprecated use acknowledgedWarningKeys; retained for old private clients. */
	val acknowledgedRevisionIds: List<UUID> = emptyList(),
	val disposition: ExportDisposition = ExportDisposition.COPY,
)

data class ContentPackSummaryResponse(
	val id: UUID,
	val generationRunId: UUID,
	val status: String,
	val title: String?,
)

data class ContentPackPageResponse(
	val items: List<ContentPackSummaryResponse>,
	val page: Int,
	val size: Int,
	val totalItems: Long,
	val totalPages: Int,
)

data class ContentPackResponse(
	val id: UUID,
	val generationRunId: UUID,
	val status: String,
	val title: String?,
	val variant: ContentVariantResponse,
)

data class ContentVariantResponse(
	val id: UUID,
	val status: String,
	val revisionId: UUID,
	val revisionNumber: Int,
	val lexicalContent: JsonNode,
	val sentences: List<ContentSentenceResponse>,
	val sources: List<ContentSourceResponse>,
)

data class ContentSentenceResponse(
	val id: UUID,
	val revisionId: UUID,
	val revisionNumber: Int,
	val orderIndex: Int,
	val body: String,
	val origin: String,
	val citations: List<ContentCitationResponse>,
)

data class ContentCitationResponse(
	val evidenceId: UUID,
	val provider: String,
	val sourceLabel: String,
	val originalUrl: String,
)

data class ContentSourceResponse(
	val evidenceId: UUID,
	val provider: String,
	val sourceLabel: String,
	val originalUrl: String,
	val statementIds: List<UUID>,
)

data class ExportWarningResponse(
	val key: String,
	val sentenceNumber: Int,
	val excerpt: String,
)

data class ContentExportResponse(
	val exportId: UUID,
	val artifactRevisionId: UUID,
	val artifactRevisionNumber: Int,
	val disposition: ExportDisposition,
	val filename: String,
	val mediaType: String,
	val text: String,
	val unresolvedCount: Int,
	val warningAcknowledged: Boolean,
	val includeSources: Boolean,
)
