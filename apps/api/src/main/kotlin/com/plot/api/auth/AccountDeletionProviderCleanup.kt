package com.plot.api.auth

import com.plot.api.auth.workos.WorkOSOrganizationDeletionGateway
import com.plot.api.auth.workos.WorkOSUserDeletionGateway
import com.plot.api.persistence.SqlExecutor
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/** Retries provider deletion after local account access has been removed. */
@Service
class AccountDeletionProviderCleanup(
	private val sql: SqlExecutor,
	private val userGateway: WorkOSUserDeletionGateway,
	private val organizationGateway: WorkOSOrganizationDeletionGateway,
) {
	fun cleanup(workOSUserId: String) {
		try {
			val pending = sql.queryForObject(
				"select workos_user_id from account_deletion_provider_cleanup where workos_user_id = ?",
				String::class.java,
				workOSUserId,
			) ?: return
			userGateway.delete(pending)
			val organizations = sql.query(
				"select workos_organization_id from account_deletion_provider_organizations where workos_user_id = ?",
				{ row, _ -> requireNotNull(row.getString("workos_organization_id")) },
				pending,
			)
			organizations.forEach { organizationGateway.delete(it) }
			sql.update("delete from account_deletion_provider_cleanup where workos_user_id = ?", pending)
		} catch (failure: RuntimeException) {
			logger.warn("Account provider cleanup will retry", failure)
			runCatching {
				sql.update(
					"update account_deletion_provider_cleanup set last_attempt_at = now(), last_error = ? where workos_user_id = ?",
					failure.javaClass.simpleName,
					workOSUserId,
				)
			}
		}
	}

	@Scheduled(fixedDelayString = "\${plot.account-deletion.cleanup-delay:PT1M}")
	fun retryPending() {
		val pending = sql.query(
			"select workos_user_id from account_deletion_provider_cleanup order by created_at asc limit 20",
			{ row, _ -> requireNotNull(row.getString("workos_user_id")) },
		)
		pending.forEach(::cleanup)
	}

	private companion object {
		val logger = LoggerFactory.getLogger(AccountDeletionProviderCleanup::class.java)
	}
}
