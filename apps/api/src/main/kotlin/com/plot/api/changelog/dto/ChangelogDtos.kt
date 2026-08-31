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
)

data class PublicChangelogResponse(
	val workspaceSlug: String,
	val workspaceName: String,
	val logoUrl: String?,
	val entries: List<PublicChangelogEntrySummaryResponse>,
)
