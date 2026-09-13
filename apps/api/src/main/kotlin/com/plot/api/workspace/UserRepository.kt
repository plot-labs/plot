package com.plot.api.workspace

import com.plot.api.persistence.generated.tables.Users.Companion.USERS
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class UserRepository(
	private val dsl: DSLContext,
) {
	fun findById(id: UUID): Optional<User> = Optional.ofNullable(select().where(USERS.ID.eq(id)).fetchOne()?.toModel())

	fun findByEmail(email: String): User? = select()
		.where(USERS.EMAIL.eq(email))
		.fetchOne()
		?.toModel()

	fun findByEmailIgnoreCase(email: String): User? = select()
		.where(USERS.EMAIL.equalIgnoreCase(email))
		.fetchOne()
		?.toModel()

	fun save(user: User): User {
		val updated = dsl.update(USERS)
			.set(USERS.EMAIL, user.email)
			.set(USERS.DISPLAY_NAME, user.displayName)
			.set(USERS.STATUS, user.status)
			.set(USERS.CREATED_AT, user.createdAt.toOffsetDateTime())
			.set(USERS.UPDATED_AT, user.updatedAt.toOffsetDateTime())
			.where(USERS.ID.eq(user.id))
			.execute()
		if (updated == 0) {
			dsl.insertInto(USERS)
				.set(USERS.ID, user.id)
				.set(USERS.EMAIL, user.email)
				.set(USERS.DISPLAY_NAME, user.displayName)
				.set(USERS.STATUS, user.status)
				.set(USERS.CREATED_AT, user.createdAt.toOffsetDateTime())
				.set(USERS.UPDATED_AT, user.updatedAt.toOffsetDateTime())
				.execute()
		}
		return user
	}

	private fun select() = dsl.select(
		USERS.ID,
		USERS.EMAIL,
		USERS.DISPLAY_NAME,
		USERS.STATUS,
		USERS.CREATED_AT,
		USERS.UPDATED_AT,
	).from(USERS)

	private fun Record.toModel() = User(
		id = requireNotNull(get(USERS.ID)),
		email = requireNotNull(get(USERS.EMAIL)),
		displayName = requireNotNull(get(USERS.DISPLAY_NAME)),
		status = requireNotNull(get(USERS.STATUS)),
		createdAt = requireNotNull(get(USERS.CREATED_AT)).toInstant(),
		updatedAt = requireNotNull(get(USERS.UPDATED_AT)).toInstant(),
	)
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(java.time.ZoneOffset.UTC)
