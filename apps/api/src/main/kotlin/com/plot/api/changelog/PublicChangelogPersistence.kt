package com.plot.api.changelog

import com.plot.api.changelog.dto.PublicChangelogEntryDetailResponse
import com.plot.api.changelog.dto.PublicChangelogEntrySummaryResponse
import com.plot.api.persistence.JooqSqlExecutor
import java.util.UUID
import org.springframework.stereotype.Repository

@Repository
class PublicChangelogPersistence(
	private val sqlExecutor: JooqSqlExecutor,
) {
	fun listEntries(workspaceId: UUID): List<PublicChangelogEntrySummaryResponse> = sqlExecutor.query(
		"""
		select id, entry_slug, title, tag_name, published_at
		from published_changelog_entries
		where workspace_id = ?
		order by published_at desc, id desc
		""".trimIndent(),
		{ rs, _ ->
			PublicChangelogEntrySummaryResponse(
				id = requireNotNull(rs.getObject(1, UUID::class.java)),
				entrySlug = requireNotNull(rs.getString(2)),
				title = requireNotNull(rs.getString(3)),
				tagName = rs.getString(4),
				publishedAt = requireNotNull(rs.getTimestamp(5)).toInstant(),
			)
		},
		workspaceId,
	)

	fun findEntry(workspaceId: UUID, entrySlug: String): PublicChangelogEntryDetailResponse? = sqlExecutor.query(
		"""
		select id, entry_slug, title, tag_name, body_markdown, published_at
		from published_changelog_entries
		where workspace_id = ? and entry_slug = ?
		""".trimIndent(),
		{ rs, _ ->
			PublicChangelogEntryDetailResponse(
				id = requireNotNull(rs.getObject(1, UUID::class.java)),
				entrySlug = requireNotNull(rs.getString(2)),
				title = requireNotNull(rs.getString(3)),
				tagName = rs.getString(4),
				bodyMarkdown = requireNotNull(rs.getString(5)),
				publishedAt = requireNotNull(rs.getTimestamp(6)).toInstant(),
				workspaceSlug = "",
				workspaceName = "",
				logoUrl = null,
			)
		},
		workspaceId,
		entrySlug,
	).firstOrNull()
}
