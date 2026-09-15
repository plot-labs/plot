package com.plot.api.source

import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.SqlRow
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/** Source-scope reads kept behind the feature-local contract. */
@Repository
class SourceScopeRepository(
	private val sql: SqlExecutor,
	private val objectMapper: ObjectMapper,
) {
	@Transactional(readOnly = true)
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): SourceScope? = sql.queryForObject(
		"""
		select id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
		       external_scope_key, external_key, display_name, url, metadata::text as metadata_json,
		       status, created_at, updated_at
		from source_scopes where workspace_id = ? and id = ?
		""".trimIndent(),
		{ row, _ -> row.toModel() }, workspaceId, id,
	)

	private fun SqlRow.toModel() = SourceScope(
		id = requireNotNull(getObject("id", UUID::class.java)),
		workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
		sourceNamespaceId = requireNotNull(getObject("source_namespace_id", UUID::class.java)),
		provider = requireNotNull(getString("provider")),
		scopeSemantics = requireNotNull(getString("scope_semantics")),
		scopeKind = requireNotNull(getString("scope_kind")),
		externalScopeKey = requireNotNull(getString("external_scope_key")),
		externalKey = getString("external_key"),
		displayName = requireNotNull(getString("display_name")),
		url = getString("url"),
		metadata = getString("metadata_json").toMap(),
		status = requireNotNull(getString("status")),
		createdAt = requireNotNull(getTimestamp("created_at")).toInstant(),
		updatedAt = requireNotNull(getTimestamp("updated_at")).toInstant(),
	)

	private fun String?.toMap(): Map<String, Any?>? = this?.let {
		@Suppress("UNCHECKED_CAST")
		objectMapper.readValue(it, Map::class.java) as Map<String, Any?>
	}
}
