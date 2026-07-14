package com.plot.api.contentpack.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class EditSentenceRequest(
	@field:NotNull val expectedRevisionNumber: Int?,
	@field:NotBlank @field:Size(max = 10_000) val body: String?,
)

enum class ExportDisposition { COPY, DOWNLOAD }

data class ExportContentVariantRequest(
	val acknowledgeUnresolved: Boolean = false,
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
	val sentences: List<ContentSentenceResponse>,
)

data class ContentSentenceResponse(
	val id: UUID,
	val revisionId: UUID,
	val revisionNumber: Int,
	val orderIndex: Int,
	val body: String,
	val origin: String,
	val verdict: String,
	val reason: String?,
	val citations: List<ContentCitationResponse>,
)

data class ContentCitationResponse(
	val evidenceId: UUID,
	val provider: String,
	val sourceLabel: String,
	val originalUrl: String,
	val snapshotExcerpt: String?,
	val status: String,
)

data class ContentExportResponse(
	val exportId: UUID,
	val disposition: ExportDisposition,
	val filename: String,
	val mediaType: String,
	val text: String,
	val unresolvedCount: Int,
	val warningAcknowledged: Boolean,
)
