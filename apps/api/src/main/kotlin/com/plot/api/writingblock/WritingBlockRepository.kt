package com.plot.api.writingblock

import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.SqlRow
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

data class WritingBlockPage(val content: List<WritingBlock>, val totalElements: Long)

@Repository
class WritingBlockRepository(
	private val sql: SqlExecutor,
	private val objectMapper: ObjectMapper,
) {
	@Transactional(readOnly = true)
	fun findAllByWorkspaceId(workspaceId: UUID, offset: Int, limit: Int): WritingBlockPage =
		page("block.workspace_id = ?", arrayOf(workspaceId), offset, limit)

	@Transactional(readOnly = true)
	fun findAllByWorkspaceIdAndSourceScopeId(
		workspaceId: UUID, sourceScopeId: UUID, offset: Int, limit: Int,
	): WritingBlockPage = page(
		"block.workspace_id = ? and ${activeMembership()}",
		arrayOf(workspaceId, workspaceId, sourceScopeId), offset, limit,
	)

	@Transactional(readOnly = true)
	fun findUnconsumedActiveAfterActivityCursor(
		workspaceId: UUID, routineId: UUID, sourceScopeId: UUID, cursorSequence: Long?, limit: Int,
	): List<WritingBlock> {
		val cursor = if (cursorSequence == null) "" else "and block.activity_sequence > ?"
		val bindings = mutableListOf<Any?>(workspaceId)
		if (cursorSequence != null) bindings += cursorSequence
		bindings.addAll(listOf(workspaceId, sourceScopeId, workspaceId, sourceScopeId, workspaceId, routineId, limit))
		return sql.query(
			"""
			$selectSql
			where block.workspace_id = ? and block.status = 'ACTIVE' $cursor
			  and ${activeSource()} and ${activeMembership()}
			  and not exists (
			    select 1 from agent_run_inputs input
			    join agent_runs run on run.workspace_id = input.workspace_id and run.id = input.agent_run_id
			    where input.workspace_id = ? and input.routine_id = ?
			      and input.writing_block_id = block.id and input.activity_sequence = block.activity_sequence
			      and input.input_kind = 'SEED' and run.status in ('QUEUED', 'RUNNING', 'SUCCEEDED')
			  )
			order by block.activity_sequence asc limit ?
			""".trimIndent(), *bindings.toTypedArray(),
		).map { it.toModel() }
	}

	@Transactional(readOnly = true)
	fun findSelectedReadable(workspaceId: UUID, sourceScopeId: UUID, ids: Collection<UUID>): List<WritingBlock> {
		if (ids.isEmpty()) return emptyList()
		val placeholders = ids.joinToString(",") { "?" }
		val bindings = mutableListOf<Any?>(workspaceId).apply {
			addAll(ids)
			addAll(listOf(workspaceId, sourceScopeId, workspaceId, sourceScopeId))
		}
		return sql.query(
			"""
			$selectSql
			where block.workspace_id = ? and block.id in ($placeholders) and block.status = 'ACTIVE'
			  and ${activeSource()} and ${activeMembership()}
			""".trimIndent(), *bindings.toTypedArray(),
		).map { it.toModel() }
	}

	@Transactional(readOnly = true)
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WritingBlock? = sql.queryForObject(
		"$selectSql where block.workspace_id = ? and block.id = ?",
		{ row, _ -> row.toModel() }, workspaceId, id,
	)

	private fun page(where: String, bindings: Array<Any?>, offset: Int, limit: Int): WritingBlockPage {
		val content = sql.query(
			"""
			$selectSql where $where
			order by block.source_created_at desc nulls last, block.external_object_key desc nulls last,
			         block.created_at desc, block.id desc offset ? limit ?
			""".trimIndent(), *bindings, offset, limit,
		).map { it.toModel() }
		val total = if (content.size < limit) offset.toLong() + content.size else sql.queryForObject(
			"select count(*) from writing_blocks block where $where", Long::class.java, *bindings,
		) ?: 0L
		return WritingBlockPage(content, total)
	}

	private fun activeSource() = """
		exists (select 1 from source_scopes scope
		        where scope.workspace_id = ? and scope.id = ? and scope.status = 'ACTIVE')
	""".trimIndent()

	private fun activeMembership() = """
		exists (select 1 from writing_block_scopes membership
		        where membership.workspace_id = ? and membership.source_scope_id = ?
		          and membership.writing_block_id = block.id and membership.status = 'ACTIVE')
	""".trimIndent()

	private fun SqlRow.toModel() = WritingBlock(
		id = requireNotNull(getObject("id", UUID::class.java)),
		workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
		sourceNamespaceId = getObject("source_namespace_id", UUID::class.java),
		externalObjectKey = getString("external_object_key"),
		sourceOrigin = requireNotNull(getString("source_origin")), sourceKind = requireNotNull(getString("source_kind")),
		title = getString("title"), body = getString("body"), url = getString("url"),
		canonicalUrl = getString("canonical_url"), author = getString("author"), platform = getString("platform"),
		metadata = getString("metadata_json").toMap(), contentHash = getString("content_hash"),
		sourceCreatedAt = getTimestamp("source_created_at")?.toInstant(),
		sourceUpdatedAt = getTimestamp("source_updated_at")?.toInstant(),
		ingestedAt = requireNotNull(getTimestamp("ingested_at")).toInstant(), status = requireNotNull(getString("status")),
		createdByUserId = getObject("created_by_user_id", UUID::class.java),
		createdAt = requireNotNull(getTimestamp("created_at")).toInstant(),
		updatedAt = requireNotNull(getTimestamp("updated_at")).toInstant(), activitySequence = getLong("activity_sequence"),
	)

	private fun String?.toMap(): Map<String, Any?>? = this?.let {
		@Suppress("UNCHECKED_CAST")
		objectMapper.readValue(it, Map::class.java) as Map<String, Any?>
	}

	private val selectSql = """
		select block.id, block.workspace_id, block.source_namespace_id, block.external_object_key,
		       block.source_origin, block.source_kind, block.title, block.body, block.url,
		       block.canonical_url, block.author, block.platform, block.metadata::text as metadata_json,
		       block.content_hash, block.source_created_at, block.source_updated_at, block.ingested_at,
		       block.status, block.created_by_user_id, block.created_at, block.updated_at, block.activity_sequence
		from writing_blocks block
	""".trimIndent()
}
