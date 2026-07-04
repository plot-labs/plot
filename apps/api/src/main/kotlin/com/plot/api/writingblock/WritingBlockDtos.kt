package com.plot.api.writingblock

import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class CreateWritingBlockRequest(
	@field:NotBlank
	val sourceOrigin: String,
	@field:NotBlank
	val sourceKind: String,
	val title: String?,
	val body: String?,
	val url: String?,
	val canonicalUrl: String?,
	val author: String?,
	val platform: String?,
	val metadata: Map<String, Any?>?,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
)

data class UpdateWritingBlockRequest(
	@field:NotBlank
	val sourceOrigin: String,
	@field:NotBlank
	val sourceKind: String,
	val title: String?,
	val body: String?,
	val url: String?,
	val canonicalUrl: String?,
	val author: String?,
	val platform: String?,
	val metadata: Map<String, Any?>?,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
)

data class WritingBlockResponse(
	val id: UUID,
	val sourceOrigin: String,
	val sourceKind: String,
	val title: String?,
	val body: String?,
	val url: String?,
	val canonicalUrl: String?,
	val author: String?,
	val platform: String?,
	val metadata: Map<String, Any?>?,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
	val ingestedAt: Instant,
	val status: String,
	val createdAt: Instant,
	val updatedAt: Instant,
)

fun WritingBlock.toResponse(): WritingBlockResponse {
	return WritingBlockResponse(
		id = id,
		sourceOrigin = sourceOrigin,
		sourceKind = sourceKind,
		title = title,
		body = body,
		url = url,
		canonicalUrl = canonicalUrl,
		author = author,
		platform = platform,
		metadata = metadata,
		sourceCreatedAt = sourceCreatedAt,
		sourceUpdatedAt = sourceUpdatedAt,
		ingestedAt = ingestedAt,
		status = status,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
