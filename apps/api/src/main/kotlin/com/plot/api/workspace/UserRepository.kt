package com.plot.api.workspace

import com.plot.api.persistence.ExposedSqlExecutor
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class UserRepository(
	private val sql: ExposedSqlExecutor,
) {
	@Transactional(readOnly = true)
	fun findById(id: UUID): Optional<User> = Optional.ofNullable(sql.execute {
		UserTable.selectAll().where { UserTable.id eq id }.singleOrNull()?.toModel()
	})

	@Transactional(readOnly = true)
	fun findByEmail(email: String): User? = sql.execute {
		UserTable.selectAll().where { UserTable.email eq email }.singleOrNull()?.toModel()
	}

	@Transactional(readOnly = true)
	fun findByEmailIgnoreCase(email: String): User? = sql.execute {
		UserTable.selectAll().where {
			UserTable.email.lowerCase() eq stringParam(email).lowerCase()
		}.singleOrNull()?.toModel()
	}

	@Transactional
	fun save(user: User): User = sql.execute {
		val updated = UserTable.update({ UserTable.id eq user.id }) {
			it[email] = user.email
			it[displayName] = user.displayName
			it[status] = user.status
			it[createdAt] = user.createdAt.atOffset(ZoneOffset.UTC)
			it[updatedAt] = user.updatedAt.atOffset(ZoneOffset.UTC)
		}
		if (updated == 0) {
			UserTable.insert {
				it[id] = user.id
				it[email] = user.email
				it[displayName] = user.displayName
				it[status] = user.status
				it[createdAt] = user.createdAt.atOffset(ZoneOffset.UTC)
				it[updatedAt] = user.updatedAt.atOffset(ZoneOffset.UTC)
			}
		}
		user
	}

	private fun ResultRow.toModel() = User(
		id = this[UserTable.id],
		email = this[UserTable.email],
		displayName = this[UserTable.displayName],
		status = this[UserTable.status],
		createdAt = this[UserTable.createdAt].toInstant(),
		updatedAt = this[UserTable.updatedAt].toInstant(),
	)
}
