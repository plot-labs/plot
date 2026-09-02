package com.plot.api.auth.persistence

import com.plot.api.persistence.generated.tables.AuthSession.Companion.AUTH_SESSION
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

data class AuthSessionRecord(
	val id: String,
	val token: String,
	val userId: String,
	val expiresAt: Instant,
	val createdAt: Instant,
	val updatedAt: Instant,
	val ipAddress: String?,
	val userAgent: String?,
)

@Repository
class AuthSessionRepository(
	private val dsl: DSLContext,
) {
	fun findByToken(token: String): AuthSessionRecord? = dsl.selectFrom(AUTH_SESSION)
		.where(AUTH_SESSION.TOKEN.eq(token))
		.fetchOne()
		?.toModel()

	fun save(session: AuthSessionRecord): AuthSessionRecord {
		val updated = dsl.update(AUTH_SESSION)
			.set(AUTH_SESSION.EXPIRES_AT, session.expiresAt.toOffsetDateTime())
			.set(AUTH_SESSION.UPDATED_AT, session.updatedAt.toOffsetDateTime())
			.set(AUTH_SESSION.IP_ADDRESS, session.ipAddress)
			.set(AUTH_SESSION.USER_AGENT, session.userAgent)
			.where(AUTH_SESSION.ID.eq(session.id))
			.execute()
		if (updated == 0) {
			dsl.insertInto(AUTH_SESSION)
				.set(AUTH_SESSION.ID, session.id)
				.set(AUTH_SESSION.TOKEN, session.token)
				.set(AUTH_SESSION.USER_ID, session.userId)
				.set(AUTH_SESSION.EXPIRES_AT, session.expiresAt.toOffsetDateTime())
				.set(AUTH_SESSION.CREATED_AT, session.createdAt.toOffsetDateTime())
				.set(AUTH_SESSION.UPDATED_AT, session.updatedAt.toOffsetDateTime())
				.set(AUTH_SESSION.IP_ADDRESS, session.ipAddress)
				.set(AUTH_SESSION.USER_AGENT, session.userAgent)
				.execute()
		}
		return session
	}

	fun deleteByToken(token: String): Int = dsl.deleteFrom(AUTH_SESSION)
		.where(AUTH_SESSION.TOKEN.eq(token))
		.execute()

	fun deleteExpired(now: Instant = Instant.now()): Int = dsl.deleteFrom(AUTH_SESSION)
		.where(AUTH_SESSION.EXPIRES_AT.le(now.toOffsetDateTime()))
		.execute()

	private fun org.jooq.Record.toModel() = AuthSessionRecord(
		id = requireNotNull(get(AUTH_SESSION.ID)),
		token = requireNotNull(get(AUTH_SESSION.TOKEN)),
		userId = requireNotNull(get(AUTH_SESSION.USER_ID)),
		expiresAt = requireNotNull(get(AUTH_SESSION.EXPIRES_AT)).toInstant(),
		createdAt = requireNotNull(get(AUTH_SESSION.CREATED_AT)).toInstant(),
		updatedAt = requireNotNull(get(AUTH_SESSION.UPDATED_AT)).toInstant(),
		ipAddress = get(AUTH_SESSION.IP_ADDRESS),
		userAgent = get(AUTH_SESSION.USER_AGENT),
	)
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
