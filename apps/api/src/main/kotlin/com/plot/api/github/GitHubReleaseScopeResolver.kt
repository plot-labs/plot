package com.plot.api.github

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
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
	private val jdbcTemplate: JdbcTemplate,
) : GitHubReleaseScopeResolver {
	override fun resolve(installationId: Long, repositoryId: Long): GitHubReleaseSourceContext? {
		val matches = jdbcTemplate.query(
			"""
			select c.workspace_id, c.created_by_user_id, c.id, b.id, n.id, sc.id,
			       sc.external_key, sc.metadata ->> 'defaultBranch'
			from connections c
			join connection_namespace_bindings b on b.workspace_id = c.workspace_id
			 and b.connection_id = c.id and b.provider = 'GITHUB' and b.status = 'ACTIVE'
			join source_namespaces n on n.workspace_id = b.workspace_id
			 and n.id = b.source_namespace_id and n.provider = 'GITHUB' and n.status = 'ACTIVE'
			join source_scopes sc on sc.workspace_id = n.workspace_id
			 and sc.source_namespace_id = n.id and sc.provider = 'GITHUB'
			 and sc.scope_kind = 'REPOSITORY' and sc.status = 'ACTIVE'
			where c.provider = 'GITHUB' and c.status = 'ACTIVE'
			  and c.external_connection_key = ? and sc.external_scope_key = ?
			""".trimIndent(),
			{ rs, _ ->
				GitHubReleaseScopeMatch(
					workspaceId = rs.getObject(1, UUID::class.java),
					createdByUserId = rs.getObject(2, UUID::class.java),
					connectionId = rs.getObject(3, UUID::class.java),
					bindingId = rs.getObject(4, UUID::class.java),
					sourceNamespaceId = rs.getObject(5, UUID::class.java),
					sourceScopeId = rs.getObject(6, UUID::class.java),
					externalKey = rs.getString(7),
					defaultBranch = rs.getString(8),
				)
			},
			installationId.toString(),
			repositoryId.toString(),
		)
		return matches.singleOrNull()?.toContext(installationId, repositoryId)
	}
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
