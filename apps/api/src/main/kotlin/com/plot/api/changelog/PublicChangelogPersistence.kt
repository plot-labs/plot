package com.plot.api.changelog

import com.plot.api.changelog.dto.PublicChangelogEntryDetailResponse
import com.plot.api.changelog.dto.PublicChangelogEntrySummaryResponse
import com.plot.api.changelog.dto.PublicChangelogCitationResponse
import com.plot.api.changelog.dto.PublicChangelogSentenceResponse
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
				sentences = emptyList(),
			)
		},
		workspaceId,
		entrySlug,
	).firstOrNull()?.let { entry ->
		entry.copy(sentences = listSentences(workspaceId, entry.id))
	}

	private fun listSentences(workspaceId: UUID, entryId: UUID): List<PublicChangelogSentenceResponse> {
		val rows = sqlExecutor.query(
			"""
			select s.order_index, s.body, c.citation_order, c.provider, c.source_label, c.original_url
			from published_changelog_entry_sentences s
			left join published_changelog_entry_citations c
			  on c.workspace_id = s.workspace_id
			 and c.published_changelog_entry_sentence_id = s.id
			where s.workspace_id = ? and s.published_changelog_entry_id = ?
			order by s.order_index, c.citation_order
			""".trimIndent(),
			{ rs, _ ->
				PublishedSentenceRow(
					orderIndex = rs.getInt(1),
					body = requireNotNull(rs.getString(2)),
					citationOrder = rs.getObject(3, Int::class.javaObjectType),
					provider = rs.getString(4),
					sourceLabel = rs.getString(5),
					originalUrl = rs.getString(6),
				)
			},
			workspaceId,
			entryId,
		)

		return rows
			.groupBy { it.orderIndex }
			.values
			.map { sentenceRows ->
				val first = sentenceRows.first()
				PublicChangelogSentenceResponse(
					orderIndex = first.orderIndex,
					body = first.body,
					citations = sentenceRows.mapNotNull { it.citation() },
				)
			}
			.sortedBy { it.orderIndex }
	}
}

private data class PublishedSentenceRow(
	val orderIndex: Int,
	val body: String,
	val citationOrder: Int?,
	val provider: String?,
	val sourceLabel: String?,
	val originalUrl: String?,
) {
	fun citation(): PublicChangelogCitationResponse? = citationOrder?.let {
		PublicChangelogCitationResponse(
			provider = requireNotNull(provider),
			sourceLabel = requireNotNull(sourceLabel),
			originalUrl = requireNotNull(originalUrl),
		)
	}
}
