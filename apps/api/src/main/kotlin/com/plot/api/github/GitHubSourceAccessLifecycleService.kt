package com.plot.api.github

import com.plot.api.persistence.JooqSqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import com.plot.api.artifact.workflow.ArtifactWorkflowExecutionPersistence
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class GitHubLifecycleProjectionResult(
	val disposition: GitHubWebhookDisposition,
	val affectedCount: Int,
)

@Service
class GitHubSourceAccessLifecycleService(
	private val sqlExecutor: JooqSqlExecutor,
	private val accessChecks: GitHubRepositoryAccessCheckPersistence,
	private val monitoringPersistence: GitHubRepositoryMonitoringPersistence,
	private val releasePersistence: GitHubReleaseLeaseStore,
	private val artifactWorkflowPersistence: ArtifactWorkflowExecutionPersistence,
) {
	fun isLifecycle(webhook: ParsedGitHubWebhook): Boolean = when (webhook.eventType) {
		"installation", "installation_repositories", "repository" -> true
		else -> false
	}

	@Transactional
	fun project(webhook: ParsedGitHubWebhook, now: Instant = Instant.now()): GitHubLifecycleProjectionResult {
		val installationId = webhook.installationId ?: return ignored()
		return when (webhook.eventType) {
			"installation" -> projectInstallation(installationId, webhook.eventAction, now)
			"installation_repositories" -> projectRepositoryGrantChange(
				installationId,
				webhook.eventAction,
				webhook.repositoryIdsAdded + webhook.repositoryIdsRemoved,
				now,
			)
			"repository" -> projectRepositoryLifecycle(installationId, webhook.eventAction, webhook.repositoryId, now)
			else -> ignored()
		}
	}

	private fun projectInstallation(
		installationId: Long,
		action: String?,
		now: Instant,
	): GitHubLifecycleProjectionResult {
		if (action !in setOf("suspend", "unsuspend", "deleted")) return ignored()
		val connections = connections(installationId)
		if (connections.isEmpty()) return ignored()
		var queued = 0
		connections.forEach { connection ->
			val scopes = scopes(connection.workspaceId, connection.id)
			when (action) {
				"suspend" -> {
					scopes.forEach { scope ->
						fenceSourceAccess(scope, now)
						markScopeInactive(scope, "ERROR", null, now)
					}
					updateConnection(connection, "ERROR", "INSTALLATION_SUSPENDED", now)
					disableMonitoring(scopes, now)
				}
				"unsuspend" -> scopes.filter { it.scopeStatus != "ACTIVE" && it.scopeReason != "USER_DISCONNECTED" }.forEach { scope ->
					queueCheck(scope, now)
					queued++
				}
				"deleted" -> {
					scopes.forEach { scope ->
						fenceSourceAccess(scope, now)
						revokeBinding(scope, now)
						markScopeInactive(scope, "DISABLED", null, now)
					}
					updateConnection(connection, "DISABLED", "INSTALLATION_UNINSTALLED", now)
					disableMonitoring(scopes, now)
				}
			}
		}
		return result(connections.size, queued)
	}

	private fun projectRepositoryGrantChange(
		installationId: Long,
		action: String?,
		repositoryIds: List<Long>,
		now: Instant,
	): GitHubLifecycleProjectionResult {
		if (action !in setOf("added", "removed")) return ignored()
		val targets = repositoryIds.distinct().flatMap { repositoryId ->
			connections(installationId).flatMap { connection ->
				scopes(connection.workspaceId, connection.id, repositoryId)
			}
		}
		if (targets.isEmpty()) return ignored()
		var queued = 0
			targets.forEach { scope ->
			if (action == "removed") {
				fenceSourceAccess(scope, now)
				revokeBinding(scope, now)
				markScopeInactive(scope, "ERROR", "GRANT_REMOVED", now)
				disableMonitoring(listOf(scope), now)
			} else if (scope.scopeStatus != "ACTIVE" && scope.scopeReason != "USER_DISCONNECTED") {
				queueCheck(scope, now)
				queued++
			}
		}
		return result(targets.size, queued)
	}

	private fun projectRepositoryLifecycle(
		installationId: Long,
		action: String?,
		repositoryId: Long?,
		now: Instant,
	): GitHubLifecycleProjectionResult {
		if (action !in setOf("deleted", "transferred") || repositoryId == null) return ignored()
		val targets = connections(installationId).flatMap { connection ->
			scopes(connection.workspaceId, connection.id, repositoryId)
		}
		if (targets.isEmpty()) return ignored()
		var queued = 0
		targets.forEach { scope ->
			if (action == "deleted") {
				fenceSourceAccess(scope, now)
				revokeBinding(scope, now)
				markScopeInactive(scope, "DISABLED", "REPOSITORY_DELETED", now)
				disableMonitoring(listOf(scope), now)
			} else if (scope.scopeReason != "USER_DISCONNECTED") {
				fenceSourceAccess(scope, now)
				markScopeInactive(scope, "ERROR", "REPOSITORY_TRANSFERRED", now)
				disableMonitoring(listOf(scope), now)
				queueCheck(scope, now)
				queued++
			}
		}
		return result(targets.size, queued)
	}

	private fun queueCheck(scope: GitHubSourceScope, now: Instant) {
		accessChecks.queue(scope.workspaceId, scope.connectionId, scope.scopeId, GitHubAccessCheckTrigger.LIFECYCLE_EVENT, now)
	}

	private fun updateConnection(connection: GitHubConnectionScope, status: String, reason: String, now: Instant) {
		sqlExecutor.update(
			"""
			update connections
			set status = ?, status_reason = ?, status_changed_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and provider = 'GITHUB'
			""".trimIndent(),
			status, reason, Timestamp.from(now), Timestamp.from(now), connection.workspaceId, connection.id,
		)
	}

	private fun markScopeInactive(scope: GitHubSourceScope, status: String, reason: String?, now: Instant) {
		sqlExecutor.update(
			"""
			update source_scopes
			set status = ?, status_reason = ?, status_changed_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and provider = 'GITHUB'
			""".trimIndent(),
			status, reason, Timestamp.from(now), Timestamp.from(now), scope.workspaceId, scope.scopeId,
		)
	}

	private fun revokeBinding(scope: GitHubSourceScope, now: Instant) {
		sqlExecutor.update(
			"""
			update connection_namespace_bindings
			set status = 'REVOKED', valid_to = ?, updated_at = ?
			where workspace_id = ? and id = ? and status <> 'REVOKED'
			""".trimIndent(),
			Timestamp.from(now), Timestamp.from(now), scope.workspaceId, scope.bindingId,
		)
	}

	private fun disableMonitoring(scopes: List<GitHubSourceScope>, now: Instant) {
		scopes.forEach { scope ->
			monitoringPersistence.disable(scope.workspaceId, scope.scopeId, now)
			releasePersistence.fenceSourceScope(scope.workspaceId, scope.scopeId, now)
			artifactWorkflowPersistence.fenceSourceScope(scope.workspaceId, scope.scopeId, now)
		}
	}

	private fun fenceSourceAccess(scope: GitHubSourceScope, now: Instant) {
		accessChecks.fence(scope.workspaceId, scope.scopeId, now)
	}

	private fun connections(installationId: Long): List<GitHubConnectionScope> = sqlExecutor.query(
		"""
		select workspace_id, id
		from connections
		where provider = 'GITHUB' and external_connection_key = ?
		order by workspace_id, id
		""".trimIndent(),
		{ rs, _ ->
			GitHubConnectionScope(
				requireNotNull(rs.getObject(1, UUID::class.java)),
				requireNotNull(rs.getObject(2, UUID::class.java)),
			)
		},
		installationId.toString(),
	)

	private fun scopes(workspaceId: UUID, connectionId: UUID, repositoryId: Long? = null): List<GitHubSourceScope> {
		val predicate = if (repositoryId == null) "" else "and sc.external_scope_key = ?"
		val args: Array<Any> = if (repositoryId == null) {
			arrayOf<Any>(workspaceId, connectionId)
		} else {
			arrayOf<Any>(workspaceId, connectionId, repositoryId.toString())
		}
		return sqlExecutor.query(
			"""
			select sc.id, b.id, c.id, sc.status, sc.status_reason, sc.workspace_id
			from source_scopes sc
			join connection_namespace_bindings b on b.workspace_id = sc.workspace_id
			  and b.source_namespace_id = sc.source_namespace_id
			join connections c on c.workspace_id = b.workspace_id and c.id = b.connection_id
			where sc.workspace_id = ? and b.connection_id = ? and c.provider = 'GITHUB'
			  and sc.provider = 'GITHUB' and sc.scope_kind = 'REPOSITORY' $predicate
			order by sc.id
			""".trimIndent(),
			{ rs, _ ->
				GitHubSourceScope(
					workspaceId = requireNotNull(rs.getObject(6, UUID::class.java)),
					scopeId = requireNotNull(rs.getObject(1, UUID::class.java)),
					bindingId = requireNotNull(rs.getObject(2, UUID::class.java)),
					connectionId = requireNotNull(rs.getObject(3, UUID::class.java)),
					scopeStatus = requireNotNull(rs.getString(4)),
					scopeReason = rs.getString(5),
				)
			},
			*args,
		)
	}

	private fun result(affectedCount: Int, queued: Int): GitHubLifecycleProjectionResult =
		GitHubLifecycleProjectionResult(
			disposition = if (queued > 0) GitHubWebhookDisposition.QUEUED else GitHubWebhookDisposition.OBSERVED,
			affectedCount = affectedCount,
		)

	private fun ignored() = GitHubLifecycleProjectionResult(GitHubWebhookDisposition.IGNORED, 0)
}

private data class GitHubConnectionScope(
	val workspaceId: UUID,
	val id: UUID,
)

private data class GitHubSourceScope(
	val workspaceId: UUID,
	val scopeId: UUID,
	val bindingId: UUID,
	val connectionId: UUID,
	val scopeStatus: String,
	val scopeReason: String?,
)
