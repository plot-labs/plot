package com.plot.api.github

import com.plot.api.common.ApiException
import com.plot.api.persistence.SqlExecutor
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Repository

data class GitHubConnectionRecord(val id: UUID, val installationId: Long, val status: String)

/** A connected repository together with the binding and installation that grant access to it. */
data class GitHubScopeRecord(
	val id: UUID,
	val sourceNamespaceId: UUID,
	val bindingId: UUID,
	val connectionId: UUID,
	val installationId: Long,
	val externalRepositoryId: Long,
	val externalKey: String,
	val displayName: String,
	val url: String,
	val status: String,
	val connectionStatus: String,
)

/** A stored connection key is a GitHub installation id only when it is a positive number. */
internal fun parseGitHubInstallationId(raw: String): Long? =
	raw.trim().toLongOrNull()?.takeIf { it > 0L }

/**
 * Reads GitHub connections and repository scopes for services that act on them.
 * Every lookup is scoped to the workspace the caller passes in.
 */
@Repository
class GitHubSourceScopeRepository(
	private val sqlExecutor: SqlExecutor,
) {
	fun findScope(workspaceId: UUID, id: UUID): GitHubScopeRecord {
		return sqlExecutor.query(
			"""
			select sc.id, sc.source_namespace_id, b.id, c.id, c.external_connection_key, sc.external_scope_key,
			       sc.external_key, sc.display_name, sc.url, sc.status, c.status
			from source_scopes sc
			join source_namespaces sn on sn.workspace_id = sc.workspace_id
			 and sn.id = sc.source_namespace_id and sn.provider = sc.provider and sn.status = 'ACTIVE'
			join connection_namespace_bindings b on b.workspace_id = sc.workspace_id
			 and b.source_namespace_id = sc.source_namespace_id and b.status = 'ACTIVE'
			join connections c on c.workspace_id = b.workspace_id and c.id = b.connection_id
			where sc.workspace_id = ? and sc.id = ? and sc.provider = 'GITHUB'
			""".trimIndent(),
			{ rs, _ ->
				GitHubScopeRecord(
					id = requireNotNull(rs.getObject(1, UUID::class.java)),
					sourceNamespaceId = requireNotNull(rs.getObject(2, UUID::class.java)),
					bindingId = requireNotNull(rs.getObject(3, UUID::class.java)),
					connectionId = requireNotNull(rs.getObject(4, UUID::class.java)),
					installationId = parseGitHubInstallationId(requireNotNull(rs.getString(5))) ?: throw connectionCorrupt(),
					externalRepositoryId = rs.getString(6)?.toLongOrNull() ?: 0L,
					externalKey = rs.getString(7).orEmpty(),
					displayName = requireNotNull(rs.getString(8)),
					url = rs.getString(9).orEmpty(),
					status = requireNotNull(rs.getString(10)),
					connectionStatus = requireNotNull(rs.getString(11)),
				)
			},
			workspaceId,
			id,
		).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "GitHub repository not found")
	}

	/** Fails unless the scope, its namespace, the binding it was read with, and the connection are all active. */
	fun requireScopeActive(workspaceId: UUID, scope: GitHubScopeRecord) {
		val active = sqlExecutor.queryForObject(
			"""
			select count(*) from source_scopes sc
			join source_namespaces sn on sn.workspace_id = sc.workspace_id
			 and sn.id = sc.source_namespace_id and sn.provider = sc.provider and sn.status = 'ACTIVE'
			join connection_namespace_bindings b on b.workspace_id = sc.workspace_id
			 and b.id = ? and b.source_namespace_id = sc.source_namespace_id and b.status = 'ACTIVE'
			join connections c on c.workspace_id = b.workspace_id and c.id = b.connection_id and c.status = 'ACTIVE'
			where sc.workspace_id = ? and sc.id = ? and sc.source_namespace_id = ? and sc.status = 'ACTIVE'
			""".trimIndent(),
			Int::class.java, scope.bindingId, workspaceId, scope.id, scope.sourceNamespaceId,
		) ?: 0
		if (active != 1) throw ApiException(HttpStatus.CONFLICT, "REPOSITORY_INACTIVE", "GitHub repository is inactive")
	}

	fun findConnection(workspaceId: UUID, id: UUID): GitHubConnectionRecord {
		return sqlExecutor.query(
			"""
			select id, external_connection_key, status
			from connections
			where workspace_id = ? and id = ? and provider = 'GITHUB'
			""".trimIndent(),
			{ rs, _ ->
				GitHubConnectionRecord(
					requireNotNull(rs.getObject(1, UUID::class.java)),
					parseGitHubInstallationId(requireNotNull(rs.getString(2))) ?: throw connectionCorrupt(),
					requireNotNull(rs.getString(3)),
				)
			},
			workspaceId,
			id,
		).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "GitHub connection not found")
	}

	private fun connectionCorrupt() = ApiException(
		HttpStatus.CONFLICT,
		"CONNECTION_CORRUPT",
		"GitHub connection installation id is invalid",
	)
}
