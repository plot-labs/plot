package com.plot.api.artifact.dto

import com.plot.api.chat.dto.ContentBriefRequest
import com.plot.api.content.ContentType
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID
import tools.jackson.databind.JsonNode

data class ContentStatementInput(
	val id: UUID?,
	@field:NotNull @field:Min(0) val orderIndex: Int?,
	@field:NotBlank @field:Size(max = 10_000) val body: String?,
	@field:Size(max = 20) val lineage: List<UUID> = emptyList(),
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
	@field:NotNull val includeSources: Boolean?,
	val acknowledgeUnresolved: Boolean = false,
	@field:Size(max = 1_000) val acknowledgedWarningKeys: List<String> = emptyList(),
	/** @deprecated use acknowledgedWarningKeys; retained for old private clients. */
	val acknowledgedRevisionIds: List<UUID> = emptyList(),
	val disposition: ExportDisposition = ExportDisposition.COPY,
)

data class ArtifactSummaryResponse(
	val id: UUID,
	val status: String,
	val title: String?,
	val contentType: String,
	val updatedAt: Instant,
	val published: Boolean = false,
)

data class ArtifactPageResponse(
	val items: List<ArtifactSummaryResponse>,
	val page: Int,
	val size: Int,
	val totalItems: Long,
	val totalPages: Int,
)

data class ArtifactPublicationResponse(
	val entryId: UUID,
	val entrySlug: String,
	val publicPath: String,
	val publishedAt: Instant,
)

data class ArtifactResponse(
	val id: UUID,
	val status: String,
	val title: String?,
	val contentType: String,
	val variant: ContentVariantResponse,
	val publication: ArtifactPublicationResponse? = null,
	val relatedArtifacts: List<RelatedArtifactSummaryResponse> = emptyList(),
)

data class RelatedArtifactSummaryResponse(
	val id: UUID,
	val title: String?,
	val contentType: String,
	val status: String,
	val updatedAt: Instant,
)

data class ReplicateContentRequest(
	val contentType: ContentType = ContentType.LAUNCH_ANNOUNCEMENT,
	@field:Size(max = 2_000) val instruction: String? = null,
	val contentProfileRevisionId: UUID? = null,
	@field:Valid val brief: ContentBriefRequest? = null,
)

data class ContentVariantResponse(
	val id: UUID,
	val status: String,
	val revisionId: UUID,
	val revisionNumber: Int,
	val lexicalContent: JsonNode,
	val sentences: List<ContentSentenceResponse>,
	val sources: List<ContentSourceResponse>,
	val documentVersion: Int = 1,
	val destinations: List<ContentBriefDestinationResponse> = emptyList(),
)

data class ContentBriefDestinationResponse(
	val id: UUID,
	val label: String,
	val url: String,
)

data class ContentVariantHistoryItemResponse(
	val position: Int,
	val createdAt: Instant,
	val cause: String,
)

data class ContentVariantHistoryDetailResponse(
	val createdAt: Instant,
	val cause: String,
	val readOnly: Boolean,
	val artifact: ArtifactResponse,
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
	val originalUrl: String?,
	val status: String = "ACTIVE",
)

data class ContentSourceResponse(
	val evidenceId: UUID,
	val provider: String,
	val sourceLabel: String,
	val originalUrl: String?,
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

data class PublishContentVariantRequest(
	@field:NotNull val expectedRevisionNumber: Int?,
	val acknowledgeUnresolved: Boolean = false,
	@field:Size(max = 1_000) val acknowledgedWarningKeys: List<String> = emptyList(),
	/** @deprecated use acknowledgedWarningKeys; retained for old private clients. */
	val acknowledgedRevisionIds: List<UUID> = emptyList(),
)

data class PublishContentVariantResponse(
	val entryId: UUID,
	val entrySlug: String,
	val publicPath: String,
	val publishedAt: Instant,
)

data class UnpublishContentVariantResponse(
	val entryId: UUID,
	val entrySlug: String,
	val publicPath: String,
	val publishedAt: Instant,
	val unpublishedAt: Instant,
)

enum class ProductDeliveryEventKind {
	CLIPBOARD_WRITE_SUCCEEDED,
	CLIPBOARD_WRITE_FAILED,
	DOWNLOAD_STARTED,
	EXTERNAL_DELIVERY_CONFIRMED,
}

data class RecordProductDeliveryEventRequest(
	@field:NotNull val kind: ProductDeliveryEventKind?,
	val exportId: UUID? = null,
	val entryId: UUID? = null,
	val clientEventId: UUID? = null,
)

data class ProductDeliveryEventResponse(
	val id: UUID,
	val kind: ProductDeliveryEventKind,
	val duplicate: Boolean = false,
)
