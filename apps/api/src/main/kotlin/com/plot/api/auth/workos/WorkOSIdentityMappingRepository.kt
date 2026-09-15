package com.plot.api.auth.workos

import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

data class WorkOSIdentityMapping(
	val workOSUserId: String,
	val plotUserId: UUID,
	val email: String,
	val emailVerified: Boolean,
)

@Repository
class WorkOSIdentityMappingRepository(
	private val sql: SqlExecutor,
) {
	fun findByWorkOSUserId(workOSUserId: String): WorkOSIdentityMapping? = sql.queryForObject(
		SELECT,
		{ row, _ ->
			WorkOSIdentityMapping(
				workOSUserId = requireNotNull(row.getString("workos_user_id")),
				plotUserId = requireNotNull(row.getObject("plot_user_id", UUID::class.java)),
				email = requireNotNull(row.getString("email")),
				emailVerified = row.getBoolean("email_verified"),
			)
		},
		workOSUserId,
	)

	fun save(mapping: WorkOSIdentityMapping, now: Instant) {
		sql.update(
			"""
			insert into workos_identity_mappings (
			  workos_user_id, plot_user_id, email, email_verified, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?)
			on conflict (workos_user_id) do update set
			  email = excluded.email,
			  email_verified = excluded.email_verified,
			  updated_at = excluded.updated_at
			where workos_identity_mappings.plot_user_id = excluded.plot_user_id
			""".trimIndent(),
			mapping.workOSUserId,
			mapping.plotUserId,
			mapping.email,
			mapping.emailVerified,
			Timestamp.from(now),
			Timestamp.from(now),
		)
	}

	private companion object {
		const val SELECT = """
			select workos_user_id, plot_user_id, email, email_verified
			from workos_identity_mappings
			where workos_user_id = ?
			"""
	}
}
