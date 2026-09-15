package com.plot.api.contentprofile

import com.plot.api.common.UuidGenerator
import com.plot.api.content.ContentProfileRevision
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.TransactionExecutor
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import tools.jackson.core.type.TypeReference

@Repository
class ContentProfilePersistence(
	private val sqlExecutor: SqlExecutor,
	private val transactionExecutor: TransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun findCurrentRevision(workspaceId: UUID): ContentProfileRevision? {
		val currentId = sqlExecutor.query(
			"select current_revision_id from workspace_content_profiles where workspace_id = ?",
			{ rs, _ -> rs.getObject(1, UUID::class.java) },
			workspaceId,
		).firstOrNull() ?: return null
		return findRevision(workspaceId, currentId)
	}

	fun findRevision(workspaceId: UUID, revisionId: UUID): ContentProfileRevision? =
		sqlExecutor.query(
			"""
			select id, workspace_id, revision_no, product_summary, primary_audience, customer_terms,
			       tone, default_locale, banned_phrases::text, created_by_user_id, created_at
			from workspace_content_profile_revisions
			where workspace_id = ? and id = ?
			""".trimIndent(),
			{ rs, _ ->
				ContentProfileRevision(
					id = requireNotNull(rs.getObject("id", UUID::class.java)),
					workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
					revisionNumber = rs.getInt("revision_no"),
					productSummary = requireNotNull(rs.getString("product_summary")),
					primaryAudience = requireNotNull(rs.getString("primary_audience")),
					customerTerms = requireNotNull(rs.getString("customer_terms")),
					tone = requireNotNull(rs.getString("tone")),
					defaultLocale = requireNotNull(rs.getString("default_locale")),
					bannedPhrases = parseBannedPhrases(rs.getString("banned_phrases")),
					createdByUserId = requireNotNull(rs.getObject("created_by_user_id", UUID::class.java)),
					createdAt = requireNotNull(rs.getTimestamp("created_at")).toInstant(),
				)
			},
			workspaceId,
			revisionId,
		).firstOrNull()

	fun appendRevision(
		workspaceId: UUID,
		createdByUserId: UUID,
		productSummary: String,
		primaryAudience: String,
		customerTerms: String,
		tone: String,
		defaultLocale: String,
		bannedPhrases: List<String>,
	): ContentProfileRevision = transactionExecutor.execute {
		val now = clock.instant()
		val nextNo = (sqlExecutor.queryForObject(
			"select coalesce(max(revision_no), 0) + 1 from workspace_content_profile_revisions where workspace_id = ?",
			Int::class.java,
			workspaceId,
		) ?: 1)
		val id = uuidGenerator.next()
		sqlExecutor.update(
			"""
			insert into workspace_content_profile_revisions (
			  id, workspace_id, revision_no, product_summary, primary_audience, customer_terms,
			  tone, default_locale, banned_phrases, created_by_user_id, created_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
			""".trimIndent(),
			id,
			workspaceId,
			nextNo,
			productSummary,
			primaryAudience,
			customerTerms,
			tone,
			defaultLocale,
			objectMapper.writeValueAsString(bannedPhrases),
			createdByUserId,
			Timestamp.from(now),
		)
		sqlExecutor.update(
			"""
			insert into workspace_content_profiles (workspace_id, current_revision_id, updated_at)
			values (?, ?, ?)
			on conflict (workspace_id) do update
			  set current_revision_id = excluded.current_revision_id,
			      updated_at = excluded.updated_at
			""".trimIndent(),
			workspaceId,
			id,
			Timestamp.from(now),
		)
		requireNotNull(findRevision(workspaceId, id))
	}

	private fun parseBannedPhrases(raw: String?): List<String> {
		if (raw.isNullOrBlank()) return emptyList()
		return objectMapper.readValue(raw, object : TypeReference<List<String>>() {})
	}
}
