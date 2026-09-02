package com.plot.api.auth.persistence

import com.plot.api.persistence.generated.tables.AuthUser.Companion.AUTH_USER
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

data class AuthUserRecord(
	val id: String,
	val name: String,
	val email: String,
	val emailVerified: Boolean,
	val image: String?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

@Repository
class AuthUserRepository(
	private val dsl: DSLContext,
) {
	fun findById(id: String): AuthUserRecord? = dsl.selectFrom(AUTH_USER)
		.where(AUTH_USER.ID.eq(id))
		.fetchOne()
		?.toModel()

	fun findByEmailIgnoreCase(email: String): AuthUserRecord? = dsl.selectFrom(AUTH_USER)
		.where(AUTH_USER.EMAIL.equalIgnoreCase(email))
		.fetchOne()
		?.toModel()

	fun save(user: AuthUserRecord): AuthUserRecord {
		val updated = dsl.update(AUTH_USER)
			.set(AUTH_USER.NAME, user.name)
			.set(AUTH_USER.EMAIL, user.email)
			.set(AUTH_USER.EMAIL_VERIFIED, user.emailVerified)
			.set(AUTH_USER.IMAGE, user.image)
			.set(AUTH_USER.UPDATED_AT, user.updatedAt.toOffsetDateTime())
			.where(AUTH_USER.ID.eq(user.id))
			.execute()
		if (updated == 0) {
			dsl.insertInto(AUTH_USER)
				.set(AUTH_USER.ID, user.id)
				.set(AUTH_USER.NAME, user.name)
				.set(AUTH_USER.EMAIL, user.email)
				.set(AUTH_USER.EMAIL_VERIFIED, user.emailVerified)
				.set(AUTH_USER.IMAGE, user.image)
				.set(AUTH_USER.CREATED_AT, user.createdAt.toOffsetDateTime())
				.set(AUTH_USER.UPDATED_AT, user.updatedAt.toOffsetDateTime())
				.execute()
		}
		return user
	}

	private fun org.jooq.Record.toModel() = AuthUserRecord(
		id = requireNotNull(get(AUTH_USER.ID)),
		name = requireNotNull(get(AUTH_USER.NAME)),
		email = requireNotNull(get(AUTH_USER.EMAIL)),
		emailVerified = requireNotNull(get(AUTH_USER.EMAIL_VERIFIED)),
		image = get(AUTH_USER.IMAGE),
		createdAt = requireNotNull(get(AUTH_USER.CREATED_AT)).toInstant(),
		updatedAt = requireNotNull(get(AUTH_USER.UPDATED_AT)).toInstant(),
	)
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
