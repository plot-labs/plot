package com.plot.api.contentprofile.dto

import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class ContentProfileResponse(
	val revisionId: UUID?,
	val revisionNumber: Int?,
	val productSummary: String,
	val primaryAudience: String,
	val customerTerms: String,
	val tone: String,
	val defaultLocale: String,
	val bannedPhrases: List<String>,
	val updatedAt: Instant?,
)

data class UpdateContentProfileRequest(
	@field:Size(max = 4_000) val productSummary: String? = null,
	@field:Size(max = 2_000) val primaryAudience: String? = null,
	@field:Size(max = 2_000) val customerTerms: String? = null,
	@field:Size(max = 2_000) val tone: String? = null,
	@field:Size(max = 32) val defaultLocale: String? = null,
	@field:Size(max = 50) val bannedPhrases: List<@Size(max = 200) String>? = null,
)
