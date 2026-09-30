package com.plot.api.auth

import com.plot.api.persistence.SqlExecutor
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

data class AccountCleanupClaim(val userId: String, val token: UUID, val attempt: Int)

/** Durable retries and lease fencing, without holding a DB transaction across provider calls. */
@Repository
class AccountCleanupPersistence(private val sql: SqlExecutor) {
	fun claim(userId: String): AccountCleanupClaim? {
		val token = UUID.randomUUID()
		return sql.queryForObject(
			"""update account_deletion_provider_cleanup set claim_token = ?, lease_until = now() + interval '10 minutes',
			attempt_count = attempt_count + 1, last_attempt_at = now()
			where workos_user_id = ? and next_attempt_at <= now() and (lease_until is null or lease_until <= now())
			returning attempt_count""",
			{ row, _ -> AccountCleanupClaim(userId, token, row.getInt(1)) }, token, userId,
		)
	}

	fun dueUsers(): List<String> = sql.query(
		"""select workos_user_id from account_deletion_provider_cleanup
		where next_attempt_at <= now() and (lease_until is null or lease_until <= now())
		order by next_attempt_at, created_at limit 20""",
		{ row, _ -> requireNotNull(row.getString(1)) },
	)

	fun organizations(claim: AccountCleanupClaim): List<String> = sql.query(
		"select workos_organization_id from account_deletion_provider_organizations where workos_user_id = ?",
		{ row, _ -> requireNotNull(row.getString(1)) }, claim.userId,
	)

	fun renew(claim: AccountCleanupClaim): Boolean = sql.update(
		"""update account_deletion_provider_cleanup set lease_until = now() + interval '10 minutes'
		where workos_user_id = ? and claim_token = ? and lease_until > now()""",
		claim.userId, claim.token,
	) == 1

	fun complete(claim: AccountCleanupClaim): Boolean = sql.update(
		"delete from account_deletion_provider_cleanup where workos_user_id = ? and claim_token = ? and lease_until > now()",
		claim.userId, claim.token,
	) == 1

	fun retry(claim: AccountCleanupClaim, errorCode: String): Boolean {
		val delayMinutes = (10 * (1 shl (claim.attempt - 1).coerceIn(0, 3))).coerceAtMost(60)
		return sql.update(
			"""update account_deletion_provider_cleanup set next_attempt_at = now() + (? * interval '1 minute'),
			last_error = ?, claim_token = null, lease_until = null
			where workos_user_id = ? and claim_token = ? and lease_until > now()""",
			delayMinutes, errorCode, claim.userId, claim.token,
		) == 1
	}

	fun nextWakeupAt(): Instant? = sql.queryForObject(
		"select min(case when claim_token is null then next_attempt_at else lease_until end) from account_deletion_provider_cleanup",
		{ row, _ -> row.getTimestamp(1)?.toInstant() },
	)
}
