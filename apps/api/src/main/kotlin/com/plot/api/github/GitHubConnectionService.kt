package com.plot.api.github

import com.plot.api.auth.RequestActorResolver
import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper

data class GitHubInstallationRequestResponse(
	val installUrl: String,
	val state: String,
	val expiresAt: Instant,
)

data class GitHubCallbackRequest(
	val state: String,
	val installationId: Long,
)

data class GitHubRepositoryResponse(
	val id: UUID?,
	val externalRepositoryId: Long,
	val owner: String,
	val name: String,
	val displayName: String,
	val url: String,
	val visibility: String,
	val status: String?,
	val monitoring: GitHubRepositoryMonitoringResponse?,
	val statusReason: String? = null,
	val accessCheckStatus: String? = null,
)

data class GitHubCallbackResponse(
	val connectionId: UUID,
	val installationId: Long,
	val repositories: List<GitHubRepositoryResponse>,
)

data class GitHubConnectionResponse(
	val id: UUID,
	val installationId: Long,
	val status: String,
	val repositories: List<GitHubRepositoryResponse>,
	val statusReason: String? = null,
)

data class GitHubAccessCheckResponse(
	val sourceScopeId: UUID,
	val status: GitHubAccessCheckStatus,
	val attemptCount: Int,
	val errorCode: String?,
	val nextAttemptAt: Instant?,
	val verifiedAt: Instant?,
)

data class GitHubConnectRepositoryRequest(
	val connectionId: UUID,
)

@Service
class GitHubConnectionService(
	private val properties: GitHubProperties,
	private val guard: GitHubGuard,
	private val devContext: DevContext,
	private val stateService: GitHubInstallationStateService,
	private val githubClient: GitHubClient,
	private val sqlExecutor: JooqSqlExecutor,
	private val transactionExecutor: JooqTransactionExecutor,
	private val objectMapper: ObjectMapper,
	private val statusRecorder: GitHubConnectionStatusRecorder,
	private val monitoringPersistence: GitHubRepositoryMonitoringPersistence,
	private val monitoringDispatcher: GitHubRepositoryMonitoringDispatcher,
	private val accessChecks: GitHubRepositoryAccessCheckPersistence,
	private val accessCheckDispatcher: GitHubRepositoryAccessCheckDispatcher,
	private val actorResolver: RequestActorResolver? = null,
) {
	fun createInstallationRequest(): GitHubInstallationRequestResponse {
		guard.requireEnabled()
		requireOwner()
		val state = stateService.create()
		val slug = properties.appSlug ?: throw notConfigured()
		val encodedState = URLEncoder.encode(state.value, StandardCharsets.UTF_8)
		return GitHubInstallationRequestResponse(
			installUrl = "${properties.webBaseUrl.trimEnd('/')}/apps/$slug/installations/new?state=$encodedState",
			state = state.value,
			expiresAt = state.expiresAt,
		)
	}

	fun completeInstallation(request: GitHubCallbackRequest): GitHubCallbackResponse {
		guard.requireEnabled()
		if (request.installationId <= 0L || request.state.isBlank()) {
			throw ApiException(HttpStatus.BAD_REQUEST, "GITHUB_CALLBACK_INVALID", "GitHub callback is invalid")
		}
		// Consume before any provider call. A failed token exchange cannot replay the state.
		val state = stateService.consume(request.state)
		requireCallbackOwner(state)
		verifyInstallationOwnership(state.userId, request.installationId)
		return activateInstallation(state.userId, state.workspaceId, request.installationId)
	}

	fun syncExistingInstallation(): GitHubCallbackResponse {
		guard.requireEnabled()
		requireOwner()
		val userId = devContext.devUserId
		val link = findLinkedGitHubAccount(userId)
			?: throw ApiException(
				HttpStatus.BAD_REQUEST,
				"GITHUB_ACCOUNT_NOT_LINKED",
				"No linked GitHub account was found; sign in with GitHub and retry",
			)
		val accessToken = link.accessToken?.takeIf { it.isNotBlank() }
			?: throw ApiException(
				HttpStatus.BAD_REQUEST,
				"GITHUB_ACCOUNT_NOT_LINKED",
				"No linked GitHub account was found; sign in with GitHub and retry",
			)
		val appId = properties.appId?.takeIf { it.isNotBlank() } ?: throw notConfigured()
		val installations = githubClient.listUserInstallations(accessToken).filter { it.appId == appId }
		if (installations.isEmpty()) {
			throw ApiException(
				HttpStatus.NOT_FOUND,
				"GITHUB_INSTALLATION_NOT_FOUND",
				"No Plot GitHub App installation was found for your account",
			)
		}
		val installationId = selectAccessibleInstallation(installations, link)
		verifyInstallationOwnership(userId, installationId)
		return activateInstallation(userId, devContext.devWorkspaceId, installationId)
	}

	private fun activateInstallation(userId: UUID, workspaceId: UUID, installationId: Long): GitHubCallbackResponse {
		val repositories = githubClient.listInstallationRepositories(installationId)
		val now = Instant.now()
		val connectionId = transactionExecutor.execute {
			val id = sqlExecutor.queryForObject(
				"""
				insert into connections (
				  id, workspace_id, provider, connection_kind, external_connection_key,
				  external_account_login, permissions, status, created_by_user_id, created_at, updated_at
				) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, ?, cast(? as jsonb), 'ACTIVE', ?, ?, ?)
				on conflict (workspace_id, provider, external_connection_key)
				do update set
				  external_account_login = excluded.external_account_login,
				  permissions = excluded.permissions,
				  status = 'ACTIVE',
				  status_reason = null,
				  status_changed_at = excluded.updated_at,
				  updated_at = excluded.updated_at
				returning id
				""".trimIndent(),
				{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) },
				UUID.randomUUID(),
				workspaceId,
				installationId.toString(),
				repositories.firstOrNull()?.owner,
				objectMapper.writeValueAsString(
					mapOf(
						"metadata" to "read",
						"pull_requests" to "read",
						"contents" to "read",
						"webhook_monitoring" to "active",
					),
				),
				userId,
				Timestamp.from(now),
				Timestamp.from(now),
			) ?: throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "GitHub connection could not be saved")
			if (monitoringPersistence.requeueAuthenticationFailures(workspaceId, id, now) > 0) {
				dispatchMonitoringAfterCommit()
			}
			id
		}
		return GitHubCallbackResponse(
			connectionId = connectionId,
			installationId = installationId,
			repositories = repositories.sortedBy { it.id }.map { it.toResponse(null) },
		)
	}

	private fun selectAccessibleInstallation(
		installations: List<GitHubUserInstallation>,
		link: LinkedGitHubAccount,
	): Long {
		val personalInstall = installations.firstOrNull {
			it.accountType.equals("USER", ignoreCase = true) && it.accountId == link.githubAccountId
		}
		val adminOrgs = installations.filter { it.accountType.equals("ORGANIZATION", ignoreCase = true) }
			.filter { isOrganizationAdmin(link.accessToken, it.accountLogin) }
		val eligibleInstallations = listOfNotNull(personalInstall) + adminOrgs
		if (eligibleInstallations.size > 1) {
			throw ApiException(
				HttpStatus.CONFLICT,
				"GITHUB_INSTALLATION_AMBIGUOUS",
				"Multiple Plot GitHub App installations were found; reconnect from GitHub settings for the account you want to use",
			)
		}
		if (eligibleInstallations.size == 1) return eligibleInstallations.first().installationId
		if (installations.size == 1) return installations.first().installationId
		throw ApiException(
			HttpStatus.CONFLICT,
			"GITHUB_INSTALLATION_AMBIGUOUS",
			"Multiple Plot GitHub App installations were found; reconnect from GitHub settings for the account you want to use",
		)
	}

	@Transactional(readOnly = true)
	fun listConnections(): List<GitHubConnectionResponse> {
		guard.requireReadAccess()
		val connections = sqlExecutor.query(
			"""
			select id, external_connection_key, status, status_reason
			from connections
			where workspace_id = ? and provider = 'GITHUB'
			order by created_at desc, id desc
			""".trimIndent(),
			{ rs, _ ->
				GitHubConnectionListRow(
					requireNotNull(rs.getObject(1, UUID::class.java)),
					requireNotNull(rs.getString(2)).toLong(),
					requireNotNull(rs.getString(3)),
					rs.getString(4),
				)
			},
			devContext.devWorkspaceId,
		)
		return connections.map { (id, installationId, status, statusReason) ->
			GitHubConnectionResponse(id, installationId, status, listScopesForConnection(id), statusReason)
		}
	}

	/**
	 * Returns the installation's current GitHub grant, annotated with the local
	 * repository scope when this workspace has already selected it.  The
	 * connection lookup is deliberately tenant-scoped before calling GitHub so a
	 * guessed connection ID cannot reveal a different workspace's installation.
	 */
	fun listGrantedRepositories(connectionId: UUID): List<GitHubRepositoryResponse> {
		guard.requireEnabled()
		requireOwner()
		val connection = findConnection(connectionId)
		val scopesByRepositoryId = listScopesForConnection(connection.id)
			.associateBy { it.externalRepositoryId }
		return try {
				githubClient.listInstallationRepositories(connection.installationId)
					.sortedBy { it.id }
					.map { repository ->
						val scope = scopesByRepositoryId[repository.id]
						repository.toResponse(
							scope?.id,
							scope?.status,
							scope?.monitoring,
							scope?.statusReason,
							scope?.accessCheckStatus,
					)
					}
		} catch (exception: ApiException) {
			if (exception.error == "GITHUB_ACCESS_DENIED" || exception.error == "GITHUB_NOT_FOUND") {
				statusRecorder.markNeedsReauth(connection.id)
			}
			throw exception
		}
	}

	fun connectRepository(externalRepositoryId: Long, request: GitHubConnectRepositoryRequest): GitHubRepositoryResponse {
		guard.requireEnabled()
		requireOwner()
		if (externalRepositoryId <= 0L) throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Repository ID is invalid")
		val connection = findConnection(request.connectionId)
		if (connection.status != "ACTIVE") throw ApiException(HttpStatus.CONFLICT, "CONNECTION_INACTIVE", "GitHub connection is inactive")
		val grantedRepository = githubClient.listInstallationRepositories(connection.installationId)
			.firstOrNull { it.id == externalRepositoryId }
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "GitHub repository is not granted to this installation")
		val repository = githubClient.verifyRepositoryAccess(
			connection.installationId,
			externalRepositoryId,
			grantedRepository.owner,
			grantedRepository.name,
		)
		val now = Instant.now()
		val (id, monitoring) = transactionExecutor.execute {
			val namespaceId = bindRepositoryNamespace(connection.id, repository, now)
			val scopeId = sqlExecutor.queryForObject(
				"""
				insert into source_scopes (
				  id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
				  external_scope_key, external_key, display_name, url, metadata, status, created_at, updated_at
				) values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, ?, ?, cast(? as jsonb), 'ACTIVE', ?, ?)
				on conflict (workspace_id, source_namespace_id, scope_kind, external_scope_key)
				do update set
				  external_key = excluded.external_key,
				  display_name = excluded.display_name,
				  url = excluded.url,
				  metadata = excluded.metadata,
				  status = 'ACTIVE',
				  status_reason = null,
				  status_changed_at = excluded.updated_at,
				  updated_at = excluded.updated_at
				returning id
				""".trimIndent(),
				{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) },
				UUID.randomUUID(),
				devContext.devWorkspaceId,
				namespaceId,
				repository.id.toString(),
				"${repository.owner}/${repository.name}",
				"${repository.owner}/${repository.name}",
				repository.url,
				objectMapper.writeValueAsString(mapOf("repositoryId" to repository.id, "defaultBranch" to repository.defaultBranch, "visibility" to repository.visibility)),
				Timestamp.from(now),
				Timestamp.from(now),
			) ?: throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "GitHub repository could not be saved")
			val monitoring = monitoringPersistence.activate(devContext.devWorkspaceId, scopeId, now)
			dispatchMonitoringAfterCommit()
			scopeId to monitoring
		}
		return repository.toResponse(id, "ACTIVE", monitoring.toResponse())
	}

	@Transactional
	fun disconnectRepository(id: UUID) {
		guard.requireEnabled()
		requireOwner()
		val now = Instant.now()
		val updated = sqlExecutor.update(
			"""
			update source_scopes
			set status = 'DISABLED', status_reason = 'USER_DISCONNECTED', status_changed_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and provider = 'GITHUB' and scope_kind = 'REPOSITORY'
			""".trimIndent(),
			Timestamp.from(now), Timestamp.from(now),
			devContext.devWorkspaceId,
			id,
		)
		if (updated != 1) throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "GitHub repository not found")
		monitoringPersistence.disable(devContext.devWorkspaceId, id, now)
		accessChecks.fence(devContext.devWorkspaceId, id, now)
	}

	@Transactional(readOnly = true)
	fun getMonitoring(sourceScopeId: UUID): GitHubRepositoryMonitoringResponse {
		guard.requireReadAccess()
		findScope(sourceScopeId)
		return monitoringPersistence.find(devContext.devWorkspaceId, sourceScopeId)?.toResponse()
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "GitHub repository monitoring not found")
	}

	@Transactional
	fun retryMonitoring(sourceScopeId: UUID): GitHubRepositoryMonitoringResponse {
		guard.requireEnabled()
		requireOwner()
		val scope = findScope(sourceScopeId)
		requireScopeActive(scope)
		val current = monitoringPersistence.find(devContext.devWorkspaceId, sourceScopeId)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "GitHub repository monitoring not found")
		if (current.analysisStatus == GitHubRepositoryAnalysisStatus.QUEUED ||
			current.analysisStatus == GitHubRepositoryAnalysisStatus.ANALYZING
		) {
			return current.toResponse()
		}
		if (current.analysisStatus != GitHubRepositoryAnalysisStatus.FAILED) {
			throw ApiException(HttpStatus.CONFLICT, "MONITORING_NOT_RETRYABLE", "Repository monitoring is not retryable")
		}
		val retried = monitoringPersistence.retry(devContext.devWorkspaceId, sourceScopeId, Instant.now())
			?: throw ApiException(HttpStatus.CONFLICT, "MONITORING_NOT_RETRYABLE", "Repository monitoring is not retryable")
		dispatchMonitoringAfterCommit()
		return retried.toResponse()
	}

	@Transactional
	fun recheckAccess(sourceScopeId: UUID, trigger: GitHubAccessCheckTrigger): GitHubAccessCheckResponse {
		guard.requireEnabled()
		requireOwner()
		val scope = sqlExecutor.query(
			"""
			select c.id, sc.status_reason
			from source_scopes sc
			join source_namespaces n on n.workspace_id = sc.workspace_id and n.id = sc.source_namespace_id
			join connection_namespace_bindings b on b.workspace_id = sc.workspace_id
			  and b.source_namespace_id = sc.source_namespace_id and b.provider = 'GITHUB'
			join connections c on c.workspace_id = b.workspace_id and c.id = b.connection_id
			where sc.workspace_id = ? and sc.id = ? and sc.provider = 'GITHUB'
			  and n.provider = 'GITHUB' and c.provider = 'GITHUB'
			order by b.status = 'ACTIVE' desc, b.updated_at desc
			limit 1
			""".trimIndent(),
			{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) to rs.getString(2) },
			devContext.devWorkspaceId,
			sourceScopeId,
		).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "GitHub repository not found")
		if (scope.second == "USER_DISCONNECTED") {
			throw ApiException(HttpStatus.CONFLICT, "REPOSITORY_DISCONNECTED", "GitHub repository was disconnected")
		}
		accessChecks.queue(devContext.devWorkspaceId, scope.first, sourceScopeId, trigger, Instant.now())
		val check = accessChecks.find(devContext.devWorkspaceId, sourceScopeId)
			?: throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "GitHub access check could not be queued")
		TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
			override fun afterCommit() {
				accessCheckDispatcher.dispatch()
			}
		})
		return check.toResponse()
	}

	fun findScope(id: UUID): GitHubScopeRecord = findScope(devContext.devWorkspaceId, id)

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
					installationId = requireNotNull(rs.getString(5)).toLong(),
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

	fun requireScopeActive(scope: GitHubScopeRecord) = requireScopeActive(devContext.devWorkspaceId, scope)

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

	private fun findConnection(id: UUID): GitHubConnectionRecord {
		return sqlExecutor.query(
			"""
			select id, external_connection_key, status
			from connections
			where workspace_id = ? and id = ? and provider = 'GITHUB'
			""".trimIndent(),
			{ rs, _ ->
				GitHubConnectionRecord(
					requireNotNull(rs.getObject(1, UUID::class.java)),
					requireNotNull(rs.getString(2)).toLong(),
					requireNotNull(rs.getString(3)),
				)
			},
			devContext.devWorkspaceId,
			id,
		).firstOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "GitHub connection not found")
	}

	private fun requireOwner() {
		val actor = actorResolver?.current()
		if (actor != null && actorResolver.requireWorkspace().role != "OWNER") {
			throw ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Workspace owner access is required")
		}
	}

	/** The callback has no workspace header; its one-time signed state is authoritative. */
	private fun requireCallbackOwner(state: GitHubInstallationStateBinding) {
		val actor = actorResolver?.current()
		if (actor != null && actor.userId != state.userId) {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "GitHub state does not belong to the authenticated user")
		}
		val ownerMembership = sqlExecutor.queryForObject(
			"""
			select count(*) from workspace_members
			where workspace_id = ? and user_id = ? and status = 'ACTIVE' and role = 'OWNER'
			""".trimIndent(),
			Int::class.java,
			state.workspaceId,
			state.userId,
		) ?: 0
		if (ownerMembership != 1) {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Workspace owner access is required")
		}
	}

	/**
	 * The callback only proves the state belongs to this workspace owner; the
	 * installation ID arrives from the browser and could name anyone's install.
	 * Prove the caller's linked GitHub identity controls the installation before
	 * any token or connection is issued.
	 */
	private fun verifyInstallationOwnership(userId: UUID, installationId: Long) {
		val link = findLinkedGitHubAccount(userId)
			?: throw installationNotOwned("No linked GitHub account was found; sign in with GitHub and retry")
		val installation = try {
			githubClient.getInstallation(installationId)
		} catch (exception: ApiException) {
			if (exception.error == "GITHUB_NOT_FOUND") throw installationNotOwned()
			throw exception
		}
		val owned = when (installation.account.type.uppercase()) {
			"USER" -> installation.account.id == link.githubAccountId && link.githubAccountId > 0L
			"ORGANIZATION" -> isOrganizationAdmin(link.accessToken, installation.account.login)
			else -> false
		}
		if (!owned) throw installationNotOwned()
	}

	private fun isOrganizationAdmin(accessToken: String?, orgLogin: String): Boolean {
		val token = accessToken?.takeIf { it.isNotBlank() } ?: return false
		val identity = githubClient.resolveAuthenticatedUser(token)
		return githubClient.organizationMembershipRole(token, orgLogin, identity.login) == "admin"
	}

	private fun findLinkedGitHubAccount(userId: UUID): LinkedGitHubAccount? = sqlExecutor.query(
		"""
		select a.account_id, a.access_token
		from users u
		join auth_account a on a.user_id = u.auth_subject and a.provider_id = 'github'
		where u.id = ? and u.auth_subject is not null
		order by a.updated_at desc
		limit 1
		""".trimIndent(),
		{ rs, _ ->
			LinkedGitHubAccount(
				githubAccountId = rs.getString(1)?.toLongOrNull() ?: 0L,
				accessToken = rs.getString(2),
			)
		},
		userId,
	).firstOrNull()

	private fun installationNotOwned(message: String = "GitHub installation does not belong to the authenticated user") =
		ApiException(HttpStatus.FORBIDDEN, "GITHUB_INSTALLATION_NOT_OWNED", message)

	private data class LinkedGitHubAccount(val githubAccountId: Long, val accessToken: String?)

	private fun listScopesForConnection(connectionId: UUID): List<GitHubRepositoryResponse> {
		return sqlExecutor.query(
			"""
			select sc.id, sc.external_scope_key, sc.external_key, sc.display_name, sc.url, sc.status,
			       sc.status_reason, ac.status, sc.metadata ->> 'visibility'
			from source_scopes sc
			join connection_namespace_bindings b on b.workspace_id = sc.workspace_id
			 and b.source_namespace_id = sc.source_namespace_id and b.provider = 'GITHUB'
			left join github_repository_access_checks ac on ac.workspace_id = sc.workspace_id
			 and ac.source_scope_id = sc.id
			where sc.workspace_id = ? and b.connection_id = ? and sc.provider = 'GITHUB'
			order by sc.display_name, sc.id
			""".trimIndent(),
			{ rs, _ ->
				val externalKey = rs.getString(3).orEmpty()
				GitHubRepositoryResponse(
					id = requireNotNull(rs.getObject(1, UUID::class.java)),
					externalRepositoryId = requireNotNull(rs.getString(2)).toLong(),
					owner = externalKey.substringBefore('/'),
					name = externalKey.substringAfter('/', requireNotNull(rs.getString(4))),
					displayName = requireNotNull(rs.getString(4)),
					url = rs.getString(5).orEmpty(),
					visibility = rs.getString(9) ?: "PUBLIC",
					status = rs.getString(6),
					monitoring = monitoringPersistence.find(
						devContext.devWorkspaceId,
						requireNotNull(rs.getObject(1, UUID::class.java)),
					)?.toResponse(),
					statusReason = rs.getString(7),
					accessCheckStatus = rs.getString(8),
				)
			},
			devContext.devWorkspaceId,
			connectionId,
		)
	}

	private fun bindRepositoryNamespace(connectionId: UUID, repository: GitHubRepository, now: Instant): UUID {
		val namespaceKey = "repository:${repository.id}"
		val namespaceId = sqlExecutor.queryForObject(
			"""
			insert into source_namespaces (
			 id, workspace_id, provider, namespace_kind, external_namespace_key,
			 display_name, status, created_at, updated_at
			) values (?, ?, 'GITHUB', ?, ?, ?, 'ACTIVE', ?, ?)
			on conflict (workspace_id, provider, namespace_kind, external_namespace_key)
			do update set status = 'ACTIVE', updated_at = excluded.updated_at
			returning id
			""".trimIndent(),
			UUID::class.java,
			UUID.randomUUID(), devContext.devWorkspaceId, "REPOSITORY", namespaceKey,
			"${repository.owner}/${repository.name}", Timestamp.from(now), Timestamp.from(now),
		) ?: throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "GitHub namespace could not be saved")
		sqlExecutor.update(
			"""
			update connection_namespace_bindings
			set status = 'REVOKED', valid_to = ?, updated_at = ?
			where workspace_id = ? and provider = 'GITHUB' and source_namespace_id = ?
			  and connection_id <> ? and status = 'ACTIVE'
			""".trimIndent(), Timestamp.from(now), Timestamp.from(now), devContext.devWorkspaceId, namespaceId, connectionId,
		)
		sqlExecutor.update(
			"""
			insert into connection_namespace_bindings (
			 id, workspace_id, provider, connection_id, source_namespace_id, capabilities,
			 status, valid_from, created_at, updated_at
			) values (?, ?, 'GITHUB', ?, ?, cast(? as jsonb), 'ACTIVE', ?, ?, ?)
			on conflict (workspace_id, connection_id, source_namespace_id)
			do update set capabilities = excluded.capabilities, status = 'ACTIVE', valid_to = null,
			              updated_at = excluded.updated_at
			""".trimIndent(),
			UUID.randomUUID(), devContext.devWorkspaceId, connectionId, namespaceId,
			objectMapper.writeValueAsString(
				mapOf(
					"metadata" to "read",
					"pull_requests" to "read",
					"contents" to "read",
					"webhook_monitoring" to "active",
				),
			),
			Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)
		return namespaceId
	}

	private fun dispatchMonitoringAfterCommit() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
				override fun afterCommit() {
					monitoringDispatcher.dispatch()
				}
			})
		} else {
			monitoringDispatcher.dispatch()
		}
	}

	private fun notConfigured() = ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_NOT_CONFIGURED", "GitHub is not configured")
}

@Service
class GitHubConnectionStatusRecorder(
	private val devContext: DevContext,
	private val sqlExecutor: JooqSqlExecutor,
) {
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
	fun markNeedsReauth(connectionId: UUID) {
		updateStatus(connectionId, devContext.devWorkspaceId)
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
	fun markNeedsReauthForWorkspace(connectionId: UUID, workspaceId: UUID) {
		updateStatus(connectionId, workspaceId)
	}

	private fun updateStatus(connectionId: UUID, workspaceId: UUID) {
		sqlExecutor.update(
			"""
			update connections
			set status = 'NEEDS_REAUTH', status_reason = 'AUTH_EXPIRED', status_changed_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and status = 'ACTIVE'
			""".trimIndent(),
			Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
			workspaceId,
			connectionId,
		)
	}
}

data class GitHubConnectionRecord(val id: UUID, val installationId: Long, val status: String)

private data class GitHubConnectionListRow(
	val id: UUID,
	val installationId: Long,
	val status: String,
	val statusReason: String?,
)

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

private fun GitHubRepository.toResponse(
	id: UUID?,
	status: String? = null,
	monitoring: GitHubRepositoryMonitoringResponse? = null,
	statusReason: String? = null,
	accessCheckStatus: String? = null,
): GitHubRepositoryResponse = GitHubRepositoryResponse(
	id = id,
	externalRepositoryId = this.id,
	owner = owner,
	name = name,
	displayName = "$owner/$name",
	url = url,
	visibility = visibility,
	status = status,
	monitoring = monitoring,
	statusReason = statusReason,
	accessCheckStatus = accessCheckStatus,
)

internal fun GitHubRepositoryAccessCheckRecord.toResponse() = GitHubAccessCheckResponse(
	sourceScopeId = sourceScopeId,
	status = status,
	attemptCount = attemptCount,
	errorCode = errorCode,
	nextAttemptAt = nextAttemptAt,
	verifiedAt = verifiedAt,
)
