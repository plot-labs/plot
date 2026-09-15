package com.plot.api.github

import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.SqlRow
import java.util.UUID
import org.springframework.stereotype.Repository

data class GitHubReleaseSourceContext(
	val workspaceId: UUID,
	val createdByUserId: UUID,
	val connectionId: UUID,
	val bindingId: UUID,
	val sourceNamespaceId: UUID,
	val sourceScopeId: UUID,
	val installationId: Long,
	val repositoryId: Long,
	val owner: String,
	val repository: String,
	val defaultBranch: String,
)

interface GitHubReleaseScopeResolver {
	fun resolve(installationId: Long, repositoryId: Long): GitHubReleaseSourceContext?
}

@Repository
class JdbcGitHubReleaseScopeResolver(
	private val sqlExecutor: SqlExecutor,
) : GitHubReleaseScopeResolver {
	override fun resolve(installationId: Long, repositoryId: Long): GitHubReleaseSourceContext? {
		val matches = sqlExecutor.query(
			"""
			select c.workspace_id, c.created_by_user_id, c.id as connection_id,
			       b.id as binding_id, n.id as source_namespace_id, s.id as source_scope_id,
			       s.external_key, s.metadata ->> 'defaultBranch' as default_branch
			from connections c
			join connection_namespace_bindings b
			  on b.workspace_id = c.workspace_id and b.connection_id = c.id
			  and b.provider = 'GITHUB' and b.status = 'ACTIVE'
			join source_namespaces n
			  on n.workspace_id = b.workspace_id and n.id = b.source_namespace_id
			  and n.provider = 'GITHUB' and n.status = 'ACTIVE'
			join source_scopes s
			  on s.workspace_id = n.workspace_id and s.source_namespace_id = n.id
			  and s.provider = 'GITHUB' and s.scope_kind = 'REPOSITORY' and s.status = 'ACTIVE'
			where c.provider = 'GITHUB' and c.status = 'ACTIVE'
			  and c.external_connection_key = ? and s.external_scope_key = ?
			""".trimIndent(),
			{ row, _ -> row.toMatch() },
			installationId.toString(),
			repositoryId.toString(),
		)
		return matches.singleOrNull()?.toContext(installationId, repositoryId)
	}

	private fun SqlRow.toMatch() = GitHubReleaseScopeMatch(
		workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
		createdByUserId = getObject("created_by_user_id", UUID::class.java),
		connectionId = requireNotNull(getObject("connection_id", UUID::class.java)),
		bindingId = requireNotNull(getObject("binding_id", UUID::class.java)),
		sourceNamespaceId = requireNotNull(getObject("source_namespace_id", UUID::class.java)),
		sourceScopeId = requireNotNull(getObject("source_scope_id", UUID::class.java)),
		externalKey = getString("external_key"),
		defaultBranch = getString("default_branch"),
	)
}

private data class GitHubReleaseScopeMatch(
	val workspaceId: UUID,
	val createdByUserId: UUID?,
	val connectionId: UUID,
	val bindingId: UUID,
	val sourceNamespaceId: UUID,
	val sourceScopeId: UUID,
	val externalKey: String?,
	val defaultBranch: String?,
) {
	fun toContext(installationId: Long, repositoryId: Long): GitHubReleaseSourceContext? {
		val owner = externalKey.orEmpty().substringBefore('/').takeIf { it.isNotBlank() } ?: return null
		val repository = externalKey.orEmpty().substringAfter('/', "").takeIf { it.isNotBlank() } ?: return null
		val branch = defaultBranch?.takeIf { it.isNotBlank() } ?: return null
		val creator = createdByUserId ?: return null
		return GitHubReleaseSourceContext(
			workspaceId = workspaceId,
			createdByUserId = creator,
			connectionId = connectionId,
			bindingId = bindingId,
			sourceNamespaceId = sourceNamespaceId,
			sourceScopeId = sourceScopeId,
			installationId = installationId,
			repositoryId = repositoryId,
			owner = owner,
			repository = repository,
			defaultBranch = branch,
		)
	}
}
