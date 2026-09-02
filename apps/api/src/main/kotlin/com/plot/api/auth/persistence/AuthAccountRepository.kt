package com.plot.api.auth.persistence

import com.plot.api.persistence.generated.tables.AuthAccount.Companion.AUTH_ACCOUNT
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

data class AuthAccountRecord(
	val id: String,
	val accountId: String,
	val providerId: String,
	val issuer: String,
	val userId: String,
	val accessToken: String?,
	val refreshToken: String?,
	val scope: String?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

@Repository
class AuthAccountRepository(
	private val dsl: DSLContext,
) {
	fun findByIssuerAndAccountId(issuer: String, accountId: String): AuthAccountRecord? = dsl.selectFrom(AUTH_ACCOUNT)
		.where(
			AUTH_ACCOUNT.ISSUER.eq(issuer),
			AUTH_ACCOUNT.ACCOUNT_ID.eq(accountId),
		)
		.fetchOne()
		?.toModel()

	fun save(account: AuthAccountRecord): AuthAccountRecord {
		val updated = dsl.update(AUTH_ACCOUNT)
			.set(AUTH_ACCOUNT.ACCESS_TOKEN, account.accessToken)
			.set(AUTH_ACCOUNT.REFRESH_TOKEN, account.refreshToken)
			.set(AUTH_ACCOUNT.SCOPE, account.scope)
			.set(AUTH_ACCOUNT.UPDATED_AT, account.updatedAt.toOffsetDateTime())
			.where(AUTH_ACCOUNT.ID.eq(account.id))
			.execute()
		if (updated == 0) {
			dsl.insertInto(AUTH_ACCOUNT)
				.set(AUTH_ACCOUNT.ID, account.id)
				.set(AUTH_ACCOUNT.ACCOUNT_ID, account.accountId)
				.set(AUTH_ACCOUNT.PROVIDER_ID, account.providerId)
				.set(AUTH_ACCOUNT.ISSUER, account.issuer)
				.set(AUTH_ACCOUNT.USER_ID, account.userId)
				.set(AUTH_ACCOUNT.ACCESS_TOKEN, account.accessToken)
				.set(AUTH_ACCOUNT.REFRESH_TOKEN, account.refreshToken)
				.set(AUTH_ACCOUNT.SCOPE, account.scope)
				.set(AUTH_ACCOUNT.CREATED_AT, account.createdAt.toOffsetDateTime())
				.set(AUTH_ACCOUNT.UPDATED_AT, account.updatedAt.toOffsetDateTime())
				.execute()
		}
		return account
	}

	private fun org.jooq.Record.toModel() = AuthAccountRecord(
		id = requireNotNull(get(AUTH_ACCOUNT.ID)),
		accountId = requireNotNull(get(AUTH_ACCOUNT.ACCOUNT_ID)),
		providerId = requireNotNull(get(AUTH_ACCOUNT.PROVIDER_ID)),
		issuer = requireNotNull(get(AUTH_ACCOUNT.ISSUER)),
		userId = requireNotNull(get(AUTH_ACCOUNT.USER_ID)),
		accessToken = get(AUTH_ACCOUNT.ACCESS_TOKEN),
		refreshToken = get(AUTH_ACCOUNT.REFRESH_TOKEN),
		scope = get(AUTH_ACCOUNT.SCOPE),
		createdAt = requireNotNull(get(AUTH_ACCOUNT.CREATED_AT)).toInstant(),
		updatedAt = requireNotNull(get(AUTH_ACCOUNT.UPDATED_AT)).toInstant(),
	)
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
