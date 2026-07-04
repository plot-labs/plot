package com.plot.api.writingblock.dto

import jakarta.validation.constraints.NotBlank
import java.time.Instant

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
