package com.plot.api.changelog.dto

import java.time.Instant
import java.util.UUID

data class PublicChangelogEntrySummaryResponse(
	val id: UUID,
	val entrySlug: String,
	val title: String,
	val tagName: String?,
	val publishedAt: Instant,
)

data class PublicChangelogCitationResponse(
	val provider: String,
	val sourceLabel: String,
	val originalUrl: String,
)

data class PublicChangelogSentenceResponse(
	val orderIndex: Int,
	val body: String,
	val citations: List<PublicChangelogCitationResponse>,
)

data class PublicChangelogEntryDetailResponse(
	val id: UUID,
	val entrySlug: String,
	val title: String,
	val tagName: String?,
	val bodyMarkdown: String,
	val publishedAt: Instant,
	val workspaceSlug: String,
	val workspaceName: String,
	val logoUrl: String?,
	val sentences: List<PublicChangelogSentenceResponse>,
)

data class PublicChangelogResponse(
	val workspaceSlug: String,
	val workspaceName: String,
	val logoUrl: String?,
	val entries: List<PublicChangelogEntrySummaryResponse>,
)
