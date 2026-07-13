package com.plot.api.writingblock

import com.plot.api.common.JdbcTime.timestamp
import com.plot.api.dev.DevContext
import com.plot.api.source.ImportedWritingBlock
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class WritingBlockUpsertResult(
	val blockId: UUID,
	val created: Boolean,
	val changed: Boolean,
)

private data class ExistingWritingBlock(
	val id: UUID,
	val contentHash: String?,
	val title: String?,
	val body: String?,
	val url: String?,
	val canonicalUrl: String?,
	val author: String?,
	val platform: String?,
	val metadataJson: String?,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
)

@Service
class WritingBlockImportService(
	private val devContext: DevContext,
	private val jdbcTemplate: JdbcTemplate,
	private val objectMapper: ObjectMapper,
) {
	@Transactional
	fun upsert(block: ImportedWritingBlock, now: Instant = Instant.now()): WritingBlockUpsertResult {
		jdbcTemplate.query(
			"select pg_advisory_xact_lock(hashtextextended(?, 0))",
			{ _, _ -> Unit },
			"${devContext.devWorkspaceId}:${block.sourceNamespaceId}:${block.sourceKind}:${block.externalObjectKey}",
		)
		val existing = jdbcTemplate.query(
			"""
			select id, content_hash, title, body, url, canonical_url, author, platform,
			       metadata::text as metadata_json, source_created_at, source_updated_at
			from writing_blocks
			where workspace_id = ? and source_namespace_id = ?
			  and source_kind = ? and external_object_key = ?
			""".trimIndent(),
			{ rs, _ ->
				ExistingWritingBlock(
					id = rs.getObject("id", UUID::class.java),
					contentHash = rs.getString("content_hash"),
					title = rs.getString("title"),
					body = rs.getString("body"),
					url = rs.getString("url"),
					canonicalUrl = rs.getString("canonical_url"),
					author = rs.getString("author"),
					platform = rs.getString("platform"),
					metadataJson = rs.getString("metadata_json"),
					sourceCreatedAt = rs.getTimestamp("source_created_at")?.toInstant(),
					sourceUpdatedAt = rs.getTimestamp("source_updated_at")?.toInstant(),
				)
			},
			devContext.devWorkspaceId,
			block.sourceNamespaceId,
			block.sourceKind,
			block.externalObjectKey,
		).firstOrNull()

		val metadata = objectMapper.writeValueAsString(block.metadata)
		val hash = contentHash(block.title, block.body)
		if (existing != null) {
			val changed = existing.contentHash != hash ||
				existing.title != block.title ||
				existing.body != block.body ||
				existing.url != block.url ||
				existing.canonicalUrl != block.canonicalUrl ||
				existing.author != block.author ||
				existing.platform != block.platform ||
				normalizeJson(existing.metadataJson) != normalizeJson(metadata) ||
				existing.sourceCreatedAt != block.sourceCreatedAt ||
				existing.sourceUpdatedAt != block.sourceUpdatedAt
			if (existing.sourceUpdatedAt != null && block.sourceUpdatedAt != null) {
				if (block.sourceUpdatedAt.isBefore(existing.sourceUpdatedAt)) {
					upsertMembership(block, existing.id, now)
					return WritingBlockUpsertResult(existing.id, created = false, changed = false)
				}
				if (changed && block.sourceUpdatedAt == existing.sourceUpdatedAt) {
					throw IllegalStateException("Conflicting content for equal source revision")
				}
			}
			if (!changed) {
				upsertMembership(block, existing.id, now)
				return WritingBlockUpsertResult(existing.id, created = false, changed = false)
			}
			jdbcTemplate.update(
				"""
				update writing_blocks
				set source_origin = ?, title = ?, body = ?, url = ?, canonical_url = ?, author = ?,
				    platform = ?, metadata = cast(? as jsonb), content_hash = ?, source_created_at = ?,
				    source_updated_at = ?, status = 'ACTIVE', updated_at = ?
				where workspace_id = ? and id = ?
				""".trimIndent(),
				block.sourceOrigin,
				block.title,
				block.body,
				block.url,
				block.canonicalUrl,
				block.author,
				block.platform,
				metadata,
				hash,
				timestamp(block.sourceCreatedAt),
				timestamp(block.sourceUpdatedAt),
				timestamp(now),
				devContext.devWorkspaceId,
				existing.id,
			)
			upsertMembership(block, existing.id, now)
			return WritingBlockUpsertResult(existing.id, created = false, changed = true)
		}

		val result = jdbcTemplate.queryForObject(
			"""
			insert into writing_blocks (
			  id, workspace_id, source_namespace_id, external_object_key,
			  source_origin, source_kind, title, body, url, canonical_url, author,
			  platform, metadata, content_hash, source_created_at, source_updated_at,
			  ingested_at, status, created_by_user_id, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?)
			on conflict (workspace_id, source_namespace_id, source_kind, external_object_key)
			where writing_blocks.source_namespace_id is not null
			  and writing_blocks.external_object_key is not null
			do update set
			  source_origin = excluded.source_origin,
			  title = excluded.title,
			  body = excluded.body,
			  url = excluded.url,
			  canonical_url = excluded.canonical_url,
			  author = excluded.author,
			  platform = excluded.platform,
			  metadata = excluded.metadata,
			  content_hash = excluded.content_hash,
			  source_created_at = excluded.source_created_at,
			  source_updated_at = excluded.source_updated_at,
			  status = 'ACTIVE',
			  updated_at = excluded.updated_at
			returning id, (xmax = 0) as inserted
			""".trimIndent(),
			RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getBoolean("inserted") },
			*arrayOf<Any?>(
				UUID.randomUUID(),
				devContext.devWorkspaceId,
				block.sourceNamespaceId,
				block.externalObjectKey,
				block.sourceOrigin,
				block.sourceKind,
				block.title,
				block.body,
				block.url,
				block.canonicalUrl,
				block.author,
				block.platform,
				metadata,
				hash,
				timestamp(block.sourceCreatedAt),
				timestamp(block.sourceUpdatedAt),
				timestamp(now),
				"ACTIVE",
				devContext.devUserId,
				timestamp(now),
				timestamp(now),
			),
		)

		upsertMembership(block, result.first, now)
		return WritingBlockUpsertResult(result.first, created = result.second, changed = !result.second)
	}

	private fun upsertMembership(block: ImportedWritingBlock, blockId: UUID, now: Instant) {
		jdbcTemplate.update(
			"""
			insert into writing_block_scopes (
			  id, workspace_id, source_namespace_id, writing_block_id, source_scope_id,
			  membership_kind, status, first_seen_at, last_seen_at, last_observation_id
			) values (?, ?, ?, ?, ?, 'CONTAINED_IN', 'ACTIVE', ?, ?, ?)
			on conflict (workspace_id, writing_block_id, source_scope_id)
			do update set status = 'ACTIVE', last_seen_at = excluded.last_seen_at,
			              last_observation_id = excluded.last_observation_id
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			block.sourceNamespaceId,
			blockId,
			block.sourceScopeId,
			timestamp(now),
			timestamp(now),
			block.observationId,
		)
	}

	private fun normalizeJson(value: String?): Any? = value?.let { objectMapper.readTree(it) }

	private fun contentHash(title: String?, body: String?): String {
		return java.security.MessageDigest.getInstance("SHA-256")
			.digest("${title.orEmpty()}\n${body.orEmpty()}".toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }
	}
}
