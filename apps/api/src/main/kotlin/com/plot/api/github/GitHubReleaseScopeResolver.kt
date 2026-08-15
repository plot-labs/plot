package com.plot.api.github

import com.plot.api.persistence.generated.tables.ConnectionNamespaceBindings.Companion.CONNECTION_NAMESPACE_BINDINGS
import com.plot.api.persistence.generated.tables.Connections.Companion.CONNECTIONS
import com.plot.api.persistence.generated.tables.SourceNamespaces.Companion.SOURCE_NAMESPACES
import com.plot.api.persistence.generated.tables.SourceScopes.Companion.SOURCE_SCOPES
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL.field
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
	private val dsl: DSLContext,
) : GitHubReleaseScopeResolver {
	override fun resolve(installationId: Long, repositoryId: Long): GitHubReleaseSourceContext? {
		val defaultBranch: Field<String?> = field(
			"{0} ->> 'defaultBranch'",
			String::class.java,
			SOURCE_SCOPES.METADATA,
		)
		val matches = dsl
			.select(
				CONNECTIONS.WORKSPACE_ID,
				CONNECTIONS.CREATED_BY_USER_ID,
				CONNECTIONS.ID,
				CONNECTION_NAMESPACE_BINDINGS.ID,
				SOURCE_NAMESPACES.ID,
				SOURCE_SCOPES.ID,
				SOURCE_SCOPES.EXTERNAL_KEY,
				defaultBranch,
			)
			.from(CONNECTIONS)
			.join(CONNECTION_NAMESPACE_BINDINGS).on(
				CONNECTION_NAMESPACE_BINDINGS.WORKSPACE_ID.eq(CONNECTIONS.WORKSPACE_ID),
				CONNECTION_NAMESPACE_BINDINGS.CONNECTION_ID.eq(CONNECTIONS.ID),
				CONNECTION_NAMESPACE_BINDINGS.PROVIDER.eq("GITHUB"),
				CONNECTION_NAMESPACE_BINDINGS.STATUS.eq("ACTIVE"),
			)
			.join(SOURCE_NAMESPACES).on(
				SOURCE_NAMESPACES.WORKSPACE_ID.eq(CONNECTION_NAMESPACE_BINDINGS.WORKSPACE_ID),
				SOURCE_NAMESPACES.ID.eq(CONNECTION_NAMESPACE_BINDINGS.SOURCE_NAMESPACE_ID),
				SOURCE_NAMESPACES.PROVIDER.eq("GITHUB"),
				SOURCE_NAMESPACES.STATUS.eq("ACTIVE"),
			)
			.join(SOURCE_SCOPES).on(
				SOURCE_SCOPES.WORKSPACE_ID.eq(SOURCE_NAMESPACES.WORKSPACE_ID),
				SOURCE_SCOPES.SOURCE_NAMESPACE_ID.eq(SOURCE_NAMESPACES.ID),
				SOURCE_SCOPES.PROVIDER.eq("GITHUB"),
				SOURCE_SCOPES.SCOPE_KIND.eq("REPOSITORY"),
				SOURCE_SCOPES.STATUS.eq("ACTIVE"),
			)
			.where(
				CONNECTIONS.PROVIDER.eq("GITHUB"),
				CONNECTIONS.STATUS.eq("ACTIVE"),
				CONNECTIONS.EXTERNAL_CONNECTION_KEY.eq(installationId.toString()),
				SOURCE_SCOPES.EXTERNAL_SCOPE_KEY.eq(repositoryId.toString()),
			)
			.fetch()
			.map { record -> record.toMatch(defaultBranch) }
		return matches.singleOrNull()?.toContext(installationId, repositoryId)
	}

	private fun Record.toMatch(defaultBranch: Field<String?>) = GitHubReleaseScopeMatch(
		workspaceId = requireNotNull(get(CONNECTIONS.WORKSPACE_ID)),
		createdByUserId = get(CONNECTIONS.CREATED_BY_USER_ID),
		connectionId = requireNotNull(get(CONNECTIONS.ID)),
		bindingId = requireNotNull(get(CONNECTION_NAMESPACE_BINDINGS.ID)),
		sourceNamespaceId = requireNotNull(get(SOURCE_NAMESPACES.ID)),
		sourceScopeId = requireNotNull(get(SOURCE_SCOPES.ID)),
		externalKey = get(SOURCE_SCOPES.EXTERNAL_KEY),
		defaultBranch = get(defaultBranch),
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
