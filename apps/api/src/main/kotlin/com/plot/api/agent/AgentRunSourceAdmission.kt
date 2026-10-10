package com.plot.api.agent

import com.plot.api.persistence.SqlExecutor
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

/** A workspace source that was active, and locked, when an agent run was admitted. */
data class AdmittedSource(
	val id: UUID,
	val displayName: String,
	val lifecycleVersionAt: Instant,
)

/**
 * Freezes the workspace sources an agent run may read.
 *
 * Chat and automation admission both lock the same source chain so a
 * disconnect cannot race the run registration. Call it inside the admission
 * transaction.
 */
@Component
class AgentRunSourceAdmission(
	private val sqlExecutor: SqlExecutor,
	private val registration: AgentRunRegistrationPersistence,
) {
	/** Locks every active GitHub source scope with its namespace, binding and connection. */
	fun lockActiveSources(workspaceId: UUID): List<AdmittedSource> = sqlExecutor.query(
		"""
		select scope.id, scope.display_name,
		       greatest(scope.status_changed_at, namespace.updated_at, binding.updated_at, connection.updated_at)
		         as lifecycle_version_at
		from source_scopes scope
		join source_namespaces namespace
		  on namespace.workspace_id = scope.workspace_id and namespace.id = scope.source_namespace_id
		 and namespace.provider = scope.provider and namespace.status = 'ACTIVE'
		join connection_namespace_bindings binding
		  on binding.workspace_id = namespace.workspace_id and binding.source_namespace_id = namespace.id
		 and binding.provider = namespace.provider and binding.status = 'ACTIVE'
		join connections connection
		  on connection.workspace_id = binding.workspace_id and connection.id = binding.connection_id
		 and connection.provider = binding.provider and connection.status = 'ACTIVE'
		where scope.workspace_id = ? and scope.provider = 'GITHUB' and scope.status = 'ACTIVE'
		order by scope.id
		for update of scope, namespace, binding, connection
		""".trimIndent(),
		{ rs, _ ->
			AdmittedSource(
				requireNotNull(rs.getObject("id", UUID::class.java)),
				requireNotNull(rs.getString("display_name")),
				requireNotNull(rs.getTimestamp("lifecycle_version_at")).toInstant(),
			)
		},
		workspaceId,
	)

	/** Records the locked sources as the run's readable context, in lock order. */
	fun registerContextSources(workspaceId: UUID, runId: UUID, sources: List<AdmittedSource>, now: Instant) {
		sources.forEachIndexed { index, source ->
			registration.insertSource(
				workspaceId,
				runId,
				source.id,
				source.displayName,
				AgentRunSourceRole.CONTEXT,
				index,
				"ACTIVE",
				source.lifecycleVersionAt,
				now,
			)
		}
	}

	/** Returns the first admitted source that actively contains the block, or null when none does. */
	fun findReadableBlockSource(workspaceId: UUID, blockId: UUID, sources: List<AdmittedSource>): UUID? {
		if (sources.isEmpty()) return null
		val placeholders = sources.joinToString(",") { "?" }
		return sqlExecutor.query(
			"""
			select membership.source_scope_id
			from writing_block_scopes membership
			join writing_blocks block
			  on block.workspace_id = membership.workspace_id and block.id = membership.writing_block_id
			where membership.workspace_id = ? and membership.writing_block_id = ?
			  and membership.source_scope_id in ($placeholders)
			  and membership.status = 'ACTIVE' and block.status = 'ACTIVE'
			order by membership.source_scope_id
			""".trimIndent(),
			{ rs, _ -> requireNotNull(rs.getObject("source_scope_id", UUID::class.java)) },
			workspaceId,
			blockId,
			*sources.map { it.id }.toTypedArray(),
		).firstOrNull()
	}
}
