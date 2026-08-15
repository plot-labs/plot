package com.plot.api.source

import com.plot.api.persistence.generated.tables.SourceScopes.Companion.SOURCE_SCOPES
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

/** jOOQ-backed source-scope reads kept behind the feature-local contract. */
@Repository
class SourceScopeRepository(
	private val dsl: DSLContext,
	private val objectMapper: ObjectMapper,
) {
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): SourceScope? = dsl
		.select(
			SOURCE_SCOPES.ID,
			SOURCE_SCOPES.WORKSPACE_ID,
			SOURCE_SCOPES.SOURCE_NAMESPACE_ID,
			SOURCE_SCOPES.PROVIDER,
			SOURCE_SCOPES.SCOPE_SEMANTICS,
			SOURCE_SCOPES.SCOPE_KIND,
			SOURCE_SCOPES.EXTERNAL_SCOPE_KEY,
			SOURCE_SCOPES.EXTERNAL_KEY,
			SOURCE_SCOPES.DISPLAY_NAME,
			SOURCE_SCOPES.URL,
			SOURCE_SCOPES.METADATA,
			SOURCE_SCOPES.STATUS,
			SOURCE_SCOPES.CREATED_AT,
			SOURCE_SCOPES.UPDATED_AT,
		)
		.from(SOURCE_SCOPES)
		.where(
			SOURCE_SCOPES.WORKSPACE_ID.eq(workspaceId),
			SOURCE_SCOPES.ID.eq(id),
		)
		.fetchOne()
		?.toModel()

	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<SourceScope> = dsl
		.select(
			SOURCE_SCOPES.ID,
			SOURCE_SCOPES.WORKSPACE_ID,
			SOURCE_SCOPES.SOURCE_NAMESPACE_ID,
			SOURCE_SCOPES.PROVIDER,
			SOURCE_SCOPES.SCOPE_SEMANTICS,
			SOURCE_SCOPES.SCOPE_KIND,
			SOURCE_SCOPES.EXTERNAL_SCOPE_KEY,
			SOURCE_SCOPES.EXTERNAL_KEY,
			SOURCE_SCOPES.DISPLAY_NAME,
			SOURCE_SCOPES.URL,
			SOURCE_SCOPES.METADATA,
			SOURCE_SCOPES.STATUS,
			SOURCE_SCOPES.CREATED_AT,
			SOURCE_SCOPES.UPDATED_AT,
		)
		.from(SOURCE_SCOPES)
		.where(SOURCE_SCOPES.WORKSPACE_ID.eq(workspaceId))
		.orderBy(SOURCE_SCOPES.CREATED_AT.desc())
		.fetch()
		.map { it.toModel() }

	private fun Record.toModel() = SourceScope(
		id = requireNotNull(get(SOURCE_SCOPES.ID)),
		workspaceId = requireNotNull(get(SOURCE_SCOPES.WORKSPACE_ID)),
		sourceNamespaceId = requireNotNull(get(SOURCE_SCOPES.SOURCE_NAMESPACE_ID)),
		provider = requireNotNull(get(SOURCE_SCOPES.PROVIDER)),
		scopeSemantics = requireNotNull(get(SOURCE_SCOPES.SCOPE_SEMANTICS)),
		scopeKind = requireNotNull(get(SOURCE_SCOPES.SCOPE_KIND)),
		externalScopeKey = requireNotNull(get(SOURCE_SCOPES.EXTERNAL_SCOPE_KEY)),
		externalKey = get(SOURCE_SCOPES.EXTERNAL_KEY),
		displayName = requireNotNull(get(SOURCE_SCOPES.DISPLAY_NAME)),
		url = get(SOURCE_SCOPES.URL),
		metadata = get(SOURCE_SCOPES.METADATA).toMap(),
		status = requireNotNull(get(SOURCE_SCOPES.STATUS)),
		createdAt = requireNotNull(get(SOURCE_SCOPES.CREATED_AT)).toInstant(),
		updatedAt = requireNotNull(get(SOURCE_SCOPES.UPDATED_AT)).toInstant(),
	)

	private fun JSONB?.toMap(): Map<String, Any?>? = this?.let {
		@Suppress("UNCHECKED_CAST")
		objectMapper.readValue(it.data(), Map::class.java) as Map<String, Any?>
	}
}
