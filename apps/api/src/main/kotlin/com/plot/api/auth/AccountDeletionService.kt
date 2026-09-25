package com.plot.api.auth

import com.plot.api.auth.workos.WorkOSNotConfiguredException
import com.plot.api.auth.workos.WorkOSOrganizationDeletionGateway
import com.plot.api.auth.workos.WorkOSProviderException
import com.plot.api.common.ApiException
import com.plot.api.persistence.SqlExecutor
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountDeletionService(
	private val actorResolver: RequestActorResolver,
	private val sql: SqlExecutor,
	private val organizationGateway: WorkOSOrganizationDeletionGateway,
) {
	@Transactional
	fun deleteCurrentAccount(): String {
		val actor = actorResolver.requireActor()
		val status = sql.queryForObject(
			"select status from users where id = ? for update",
			String::class.java,
			actor.userId,
		)
		if (status != "ACTIVE") throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required")

		val ownedWorkspaces = sql.query(
			"select id, polar_subscription_id, polar_subscription_status, polar_subscription_current_period_end from workspaces where created_by_user_id = ? and status <> 'DELETED' for update",
			{ row, _ ->
				OwnedWorkspace(
					requireNotNull(row.getObject("id", UUID::class.java)),
					row.getString("polar_subscription_id"),
					row.getString("polar_subscription_status"),
					row.getTimestamp("polar_subscription_current_period_end")?.toInstant(),
				)
			},
			actor.userId,
		)
		val now = Instant.now()
		if (ownedWorkspaces.any {
			it.subscriptionId != null && it.subscriptionStatus?.lowercase() != "revoked" &&
				(it.subscriptionStatus?.lowercase() !in setOf("canceled", "cancelled") || it.currentPeriodEnd?.isAfter(now) == true)
		}) {
			throw ApiException(HttpStatus.CONFLICT, "ACCOUNT_SUBSCRIPTION_ACTIVE", "Cancel the workspace subscription and wait until its billing period ends before deleting your account")
		}
		if (ownedWorkspaces.any { workspace ->
			sql.queryForObject(
				"select exists(select 1 from workspace_members where workspace_id = ? and user_id <> ? and status = 'ACTIVE')",
				Boolean::class.javaObjectType,
				workspace.id,
				actor.userId,
			) == true
		}) {
			throw ApiException(HttpStatus.CONFLICT, "ACCOUNT_WORKSPACE_HAS_MEMBERS", "Remove other workspace members before deleting your account")
		}
		val ownedOrganizations = sql.query(
			"""
			select m.workos_organization_id
			from workos_organization_mappings m
			join workspaces w on w.id = m.workspace_id
			where m.workos_user_id = ? and w.created_by_user_id = ?
			""".trimIndent(),
			{ row, _ -> requireNotNull(row.getString("workos_organization_id")) },
			actor.workOSUserId, actor.userId,
		)
		try {
			if (ownedOrganizations.any { organizationGateway.hasOtherActiveMembers(it, actor.workOSUserId) }) {
				throw ApiException(HttpStatus.CONFLICT, "ACCOUNT_WORKSPACE_HAS_MEMBERS", "Remove other workspace members before deleting your account")
			}
		} catch (failure: WorkOSNotConfiguredException) {
			throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKOS_NOT_CONFIGURED", "Account deletion is unavailable").also { it.initCause(failure) }
		} catch (failure: WorkOSProviderException) {
			throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKOS_PROVIDER_UNAVAILABLE", "Workspace membership could not be checked").also { it.initCause(failure) }
		}

		// Record provider resources before removing their local mappings. The
		// cleanup worker can retry even after this account loses access.
		sql.update("insert into account_deletion_provider_cleanup (workos_user_id) values (?)", actor.workOSUserId)
		ownedOrganizations.forEach { organizationId ->
			sql.update(
				"insert into account_deletion_provider_organizations (workos_user_id, workos_organization_id) values (?, ?)",
				actor.workOSUserId, organizationId,
			)
		}

		// Keep historical workspace records and their foreign keys intact.
		sql.update("delete from github_product_oauth_states where user_id = ?", actor.userId)
		sql.update("delete from github_product_credentials where user_id = ?", actor.userId)
		sql.update("delete from github_installation_states where user_id = ?", actor.userId)
		sql.update("delete from auth_user where id = (select auth_subject from users where id = ?)", actor.userId)
		sql.update("update workspace_members set status = 'INACTIVE', workos_membership_id = null, updated_at = now() where user_id = ?", actor.userId)
		sql.update("update workspaces set status = 'DELETED', updated_at = now() where created_by_user_id = ? and status <> 'DELETED'", actor.userId)
		sql.update("delete from workos_identity_mappings where workos_user_id = ? and plot_user_id = ?", actor.workOSUserId, actor.userId)
		sql.update(
			"update users set email = ?, display_name = 'Deleted account', auth_issuer = null, auth_subject = null, status = 'DELETED', updated_at = now() where id = ?",
			"deleted-${actor.userId}@deleted.plot.invalid",
			actor.userId,
		)
		return actor.workOSUserId
	}

	private data class OwnedWorkspace(
		val id: UUID,
		val subscriptionId: String?,
		val subscriptionStatus: String?,
		val currentPeriodEnd: Instant?,
	)
}
