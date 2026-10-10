package com.plot.api.github

import com.plot.api.auth.RequestActorResolver
import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.TransactionExecutor
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.plot.api.common.AfterCommit
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

data class GitHubAvailableInstallationResponse(
	val installationId: Long,
	val accountLogin: String,
	val accountType: String,
	val connected: Boolean,
	val connectable: Boolean,
)

data class GitHubConnectionResponse(
	val id: UUID,
	val installationId: Long,
	val status: String,
	val repositories: List<GitHubRepositoryResponse>,
	val statusReason: String? = null,
	val accountLogin: String? = null,
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
	private val installationOwnership: GitHubInstallationOwnership,
	private val scopes: GitHubSourceScopeRepository,
	private val sqlExecutor: SqlExecutor,
	private val transactionExecutor: TransactionExecutor,
	private val objectMapper: ObjectMapper,
	private val statusRecorder: GitHubConnectionStatusRecorder,
	private val monitoringPersistence: GitHubRepositoryMonitoringPersistence,
	private val monitoringDispatcher: GitHubRepositoryMonitoringDispatcher,
	private val accessChecks: GitHubRepositoryAccessCheckPersistence,
	private val accessCheckDispatcher: GitHubRepositoryAccessCheckDispatcher,
	private val actorResolver: RequestActorResolver? = null,
	private val autonomy: com.plot.api.autonomy.github.GitHubAutonomyBridge,
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
		installationOwnership.verifyOwnership(state.userId, request.installationId)
		return activateInstallation(state.userId, state.workspaceId, request.installationId)
	}

	fun syncExistingInstallation(): GitHubCallbackResponse {
		guard.requireEnabled()
		requireOwner()
		val actor = actorResolver?.current()
		val userId = actor?.userId ?: devContext.devUserId
		val workspaceId = actor?.let { actorResolver.requireWorkspace().workspaceId } ?: devContext.devWorkspaceId
		return syncExistingInstallationForProductCredential(userId, workspaceId, requireOwner = actor != null)
	}

	/** Reuses only the product-owned GitHub credential, never account-auth storage. */
	fun syncExistingInstallationForProductCredential(
		userId: UUID,
		workspaceId: UUID,
		requireOwner: Boolean = true,
	): GitHubCallbackResponse {
		guard.requireEnabled()
		if (requireOwner) requireOwnerMembership(userId, workspaceId)
		val link = installationOwnership.requireLinkedAccount(userId)
		val installations = installationOwnership.accessibleInstallations(link)
		if (installations.isEmpty()) throw installationOwnership.installationNotFound()
		val installationId = installationOwnership.selectAccessibleInstallation(installations, link)
		installationOwnership.verifyOwnership(userId, installationId)
		return activateInstallation(userId, workspaceId, installationId)
	}

	/**
	 * Lists this app's installations the linked GitHub account can reach, so an owner can
	 * attach more than the one installation sync picks. Personal installations of other
	 * users are listed but not connectable, matching [GitHubInstallationOwnership.verifyOwnership].
	 */
	fun listAvailableInstallations(): List<GitHubAvailableInstallationResponse> {
		guard.requireEnabled()
		requireOwner()
		val (userId, workspaceId) = currentOwner()
		val link = installationOwnership.requireLinkedAccount(userId)
		val connected = sqlExecutor.query(
			"""
			select external_connection_key
			from connections
			where workspace_id = ? and provider = 'GITHUB' and status = 'ACTIVE'
			""".trimIndent(),
			{ rs, _ -> rs.getString(1).orEmpty() },
			workspaceId,
		).mapNotNull { parseGitHubInstallationId(it) }.toSet()
		return installationOwnership.accessibleInstallations(link)
			.sortedWith(compareBy({ !it.accountType.equals("USER", ignoreCase = true) }, { it.accountLogin.lowercase() }))
			.map {
				GitHubAvailableInstallationResponse(
					installationId = it.installationId,
					accountLogin = it.accountLogin,
					accountType = it.accountType,
					connected = it.installationId in connected,
					connectable = !it.accountType.equals("USER", ignoreCase = true) || it.accountId == link.githubAccountId,
				)
			}
	}

	/** Attaches one installation chosen by the owner; ownership is verified exactly as in sync. */
	fun connectInstallation(installationId: Long): GitHubCallbackResponse {
		guard.requireEnabled()
		requireOwner()
		val (userId, workspaceId) = currentOwner()
		val link = installationOwnership.requireLinkedAccount(userId)
		if (installationOwnership.accessibleInstallations(link).none { it.installationId == installationId }) {
			throw installationOwnership.installationNotFound()
		}
		installationOwnership.verifyOwnership(userId, installationId)
		return activateInstallation(userId, workspaceId, installationId)
	}

	private fun currentOwner(): Pair<UUID, UUID> {
		val actor = actorResolver?.current()
		val userId = actor?.userId ?: devContext.devUserId
		val workspaceId = actor?.let { actorResolver.requireWorkspace().workspaceId } ?: devContext.devWorkspaceId
		if (actor != null) requireOwnerMembership(userId, workspaceId)
		return userId to workspaceId
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

	@Transactional(readOnly = true)
	fun listConnections(): List<GitHubConnectionResponse> {
		guard.requireReadAccess()
		val connections = sqlExecutor.query(
			"""
			select id, external_connection_key, status, status_reason, external_account_login
			from connections
			where workspace_id = ? and provider = 'GITHUB'
			order by created_at desc, id desc
			""".trimIndent(),
			{ rs, _ ->
				GitHubConnectionListRow(
					requireNotNull(rs.getObject(1, UUID::class.java)),
					requireNotNull(rs.getString(2)),
					requireNotNull(rs.getString(3)),
					rs.getString(4),
					rs.getString(5)?.takeIf { it.isNotBlank() },
				)
			},
			devContext.devWorkspaceId,
		)
		return connections.mapNotNull { (id, installationKey, status, statusReason, accountLogin) ->
			val installationId = parseGitHubInstallationId(installationKey) ?: return@mapNotNull null
			GitHubConnectionResponse(id, installationId, status, listScopesForConnection(id), statusReason, accountLogin)
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
		val (id, monitoring) = transactionExecutor.execute {
            // Bootstrap checks binding validity in this transaction; use the same database clock.
            val now = requireNotNull(sqlExecutor.queryForObject("select current_timestamp", java.sql.Timestamp::class.java)).toInstant()
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
			autonomy.bootstrap(devContext.devWorkspaceId, scopeId)
			val monitoring = monitoringPersistence.activate(devContext.devWorkspaceId, scopeId, now)
			dispatchMonitoringAfterCommit()
			scopeId to monitoring
		}
		return repository.toResponse(id, "ACTIVE", monitoring.toResponse())
	}

	/**
	 * Removes a connection GitHub no longer honors, such as an installation that was
	 * replaced or deleted. Active connections are kept so a working installation is
	 * never dropped by accident; reinstalling the same installation reactivates it.
	 */
	@Transactional
	fun removeConnection(connectionId: UUID) {
		guard.requireEnabled()
		requireOwner()
		val connection = findConnection(connectionId)
		if (connection.status == "ACTIVE") {
			throw ApiException(HttpStatus.CONFLICT, "CONNECTION_ACTIVE", "An active GitHub connection cannot be removed")
		}
		val now = Instant.now()
		sqlExecutor.update(
			"""
			update connections
			set status = 'DISABLED', status_changed_at = ?, updated_at = ?
			where workspace_id = ? and id = ? and provider = 'GITHUB'
			""".trimIndent(),
			Timestamp.from(now), Timestamp.from(now),
			devContext.devWorkspaceId,
			connection.id,
		)
		// A replaced installation can share its account namespace with the current one; only stop
		// repositories this connection still owns through an active binding.
		sqlExecutor.query(
			"""
			select sc.id
			from source_scopes sc
			join connection_namespace_bindings b on b.workspace_id = sc.workspace_id
			 and b.source_namespace_id = sc.source_namespace_id and b.provider = 'GITHUB'
			where sc.workspace_id = ? and b.connection_id = ? and b.status = 'ACTIVE'
			  and sc.provider = 'GITHUB' and sc.scope_kind = 'REPOSITORY' and sc.status = 'ACTIVE'
			""".trimIndent(),
			{ rs, _ -> requireNotNull(rs.getObject(1, UUID::class.java)) },
			devContext.devWorkspaceId,
			connection.id,
		)
			.forEach { scopeId ->
				sqlExecutor.update(
					"""
					update source_scopes
					set status = 'DISABLED', status_reason = 'USER_DISCONNECTED', status_changed_at = ?, updated_at = ?
					where workspace_id = ? and id = ? and provider = 'GITHUB' and scope_kind = 'REPOSITORY'
					""".trimIndent(),
					Timestamp.from(now), Timestamp.from(now),
					devContext.devWorkspaceId,
					scopeId,
				)
				monitoringPersistence.disable(devContext.devWorkspaceId, scopeId, now)
				accessChecks.fence(devContext.devWorkspaceId, scopeId, now)
			}
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
		AfterCommit.register { accessCheckDispatcher.dispatch() }
		return check.toResponse()
	}

	private fun findScope(id: UUID): GitHubScopeRecord = scopes.findScope(devContext.devWorkspaceId, id)

	private fun requireScopeActive(scope: GitHubScopeRecord) = scopes.requireScopeActive(devContext.devWorkspaceId, scope)

	private fun findConnection(id: UUID): GitHubConnectionRecord = scopes.findConnection(devContext.devWorkspaceId, id)

	private fun requireOwner() {
		val actor = actorResolver?.current()
		if (actor != null && actorResolver.requireWorkspace().role != "OWNER") {
			throw ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Workspace owner access is required")
		}
	}

	private fun requireOwnerMembership(userId: UUID, workspaceId: UUID) {
		val ownerMembership = sqlExecutor.queryForObject(
			"""
			select count(*) from workspace_members
			where workspace_id = ? and user_id = ? and status = 'ACTIVE' and role = 'OWNER'
			""".trimIndent(),
			Int::class.java,
			workspaceId,
			userId,
		) ?: 0
		if (ownerMembership != 1) {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Workspace owner access is required")
		}
	}

	/** The callback has no workspace header; its one-time signed state is authoritative. */
	private fun requireCallbackOwner(state: GitHubInstallationStateBinding) {
		val actor = actorResolver?.current()
		if (actor != null && actor.userId != state.userId) {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "GitHub state does not belong to the authenticated user")
		}
		requireOwnerMembership(state.userId, state.workspaceId)
	}

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
					externalRepositoryId = requireNotNull(rs.getString(2)).toLongOrNull()
						?: throw ApiException(
							HttpStatus.CONFLICT,
							"SCOPE_CORRUPT",
							"GitHub repository scope id is invalid",
						),
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
		AfterCommit.runOrNow { monitoringDispatcher.dispatch() }
	}

	private fun notConfigured() = ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_NOT_CONFIGURED", "GitHub is not configured")
}

@Service
class GitHubConnectionStatusRecorder(
	private val devContext: DevContext,
	private val sqlExecutor: SqlExecutor,
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

private data class GitHubConnectionListRow(
	val id: UUID,
	val installationKey: String,
	val status: String,
	val statusReason: String?,
	val accountLogin: String?,
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
