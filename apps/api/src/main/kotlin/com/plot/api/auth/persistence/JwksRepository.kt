package com.plot.api.auth.persistence

import com.plot.api.persistence.generated.tables.AuthJwks.Companion.AUTH_JWKS
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

data class JwksRecord(
	val id: String,
	val publicKey: String,
	val privateKey: String,
	val alg: String,
	val createdAt: Instant,
	val expiresAt: Instant?,
)

@Repository
class JwksRepository(
	private val dsl: DSLContext,
) {
	fun findAllActive(now: Instant = Instant.now()): List<JwksRecord> = dsl.selectFrom(AUTH_JWKS)
		.where(AUTH_JWKS.EXPIRES_AT.isNull.or(AUTH_JWKS.EXPIRES_AT.gt(now.toOffsetDateTime())))
		.orderBy(AUTH_JWKS.CREATED_AT.desc())
		.fetch()
		.map { it.toModel() }

	fun findSigningKey(now: Instant = Instant.now()): JwksRecord? = findAllActive(now).firstOrNull()

	fun save(record: JwksRecord): JwksRecord {
		dsl.insertInto(AUTH_JWKS)
			.set(AUTH_JWKS.ID, record.id)
			.set(AUTH_JWKS.PUBLIC_KEY, record.publicKey)
			.set(AUTH_JWKS.PRIVATE_KEY, record.privateKey)
			.set(AUTH_JWKS.ALG, record.alg)
			.set(AUTH_JWKS.CREATED_AT, record.createdAt.toOffsetDateTime())
			.set(AUTH_JWKS.EXPIRES_AT, record.expiresAt?.toOffsetDateTime())
			.execute()
		return record
	}

	private fun org.jooq.Record.toModel() = JwksRecord(
		id = requireNotNull(get(AUTH_JWKS.ID)),
		publicKey = requireNotNull(get(AUTH_JWKS.PUBLIC_KEY)),
		privateKey = requireNotNull(get(AUTH_JWKS.PRIVATE_KEY)),
		alg = requireNotNull(get(AUTH_JWKS.ALG)),
		createdAt = requireNotNull(get(AUTH_JWKS.CREATED_AT)).toInstant(),
		expiresAt = get(AUTH_JWKS.EXPIRES_AT)?.toInstant(),
	)
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
