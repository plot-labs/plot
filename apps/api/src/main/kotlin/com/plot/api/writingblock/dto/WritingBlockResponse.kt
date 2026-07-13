package com.plot.api.writingblock.dto

import java.time.Instant
import java.util.UUID

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
	val sourceManaged: Boolean,
	val createdAt: Instant,
	val updatedAt: Instant,
)
