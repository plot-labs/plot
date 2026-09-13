package com.plot.api.github

import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import com.plot.api.common.UuidGenerator
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

data class GitHubCredentialBackfillReport(
	val state: String,
	val processedCount: Long,
	val migratedCount: Long,
	val quarantinedCount: Long,
	val checkpoint: String?,
)

/**
 * Resumable, operator-triggered migration of only product GitHub credentials.
 * It joins legacy rows to Plot users by the existing immutable auth_subject;
 * it never guesses a WorkOS identity from an email address.
 */
@Service
class GitHubCredentialBackfillService(
	private val properties: GitHubProperties,
	private val sql: JooqSqlExecutor,
	private val transactionExecutor: JooqTransactionExecutor,
	private val credentialRepository: GitHubProductCredentialRepository,
	private val uuidGenerator: UuidGenerator,
) {
	fun runBatch(requestedBatchSize: Int = properties.productCredentialBackfillBatchSize): GitHubCredentialBackfillReport {
		val batchSize = requestedBatchSize.coerceIn(1, 500)
		val checkpoint = readCheckpoint()
		if (checkpoint.state == "COMPLETED") return checkpoint.toReport()
		sql.update(
			"""
			update github_product_credential_backfill
			set state = 'RUNNING', started_at = coalesce(started_at, ?), updated_at = ?, last_error = null
			where id = 1
			""".trimIndent(),
			Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
		)

		val rows = loadRows(checkpoint.lastSourceId, batchSize)
		var migrated = 0L
		var quarantined = 0L
		var lastSourceId = checkpoint.lastSourceId
		try {
			rows.forEach { row ->
				lastSourceId = row.sourceAccountId
				val reason = quarantineReason(row)
				if (reason != null) {
					quarantine(row, reason)
					quarantined++
				} else {
					credentialRepository.saveActive(GitHubProductCredential(
						id = uuidGenerator.next(),
						userId = requireNotNull(row.plotUserId),
						githubAccountId = requireNotNull(row.githubAccountId),
						githubLogin = null,
						accessToken = requireNotNull(row.accessToken),
						refreshToken = row.refreshToken,
						scope = requireNotNull(row.scope),
						status = "ACTIVE",
						createdAt = row.createdAt,
						updatedAt = row.updatedAt,
						revokedAt = null,
						encryptionKeyVersion = properties.productCredentialEncryptionKeyVersion,
					))
					migrated++
				}
				advanceCheckpoint(
					lastSourceId = lastSourceId,
					processedDelta = 1,
					migratedDelta = if (reason == null) 1 else 0,
					quarantinedDelta = if (reason == null) 0 else 1,
					state = "RUNNING",
				)
			}
			if (rows.size < batchSize) {
				sql.update(
					"""
					update github_product_credential_backfill
					set state = 'COMPLETED', completed_at = ?, updated_at = ?
					where id = 1
					""".trimIndent(),
					Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
				)
			}
		} catch (failure: RuntimeException) {
			sql.update(
				"""
				update github_product_credential_backfill
				set state = 'FAILED', last_error = ?, updated_at = ?
				where id = 1
				""".trimIndent(),
				failure.safeCode(), Timestamp.from(Instant.now()),
			)
			throw failure
		}
		return readCheckpoint().toReport()
	}

	fun status(): GitHubCredentialBackfillReport = readCheckpoint().toReport()

	private fun loadRows(lastSourceId: String?, limit: Int): List<LegacyCredentialRow> {
		val base = """
			select a.id as source_account_id, a.user_id as source_user_id,
			       a.account_id, a.access_token, a.refresh_token, a.scope,
			       a.created_at, a.updated_at, u.id as plot_user_id
			from auth_account a
			left join users u on u.auth_subject = a.user_id
			where a.provider_id = 'github' and a.access_token is not null
			""".trimIndent()
		val sqlText = if (lastSourceId == null) {
			"$base order by a.id asc limit ?"
		} else {
			"$base and a.id > ? order by a.id asc limit ?"
		}
		val bindings: Array<Any?> = if (lastSourceId == null) arrayOf(limit) else arrayOf(lastSourceId, limit)
		return sql.query(sqlText, ::toLegacyRow, *bindings)
	}

	private fun quarantineReason(row: LegacyCredentialRow): String? = when {
		row.plotUserId == null -> "PLOT_USER_UNMAPPABLE"
		row.githubAccountId == null || row.githubAccountId <= 0L -> "GITHUB_ACCOUNT_ID_INVALID"
		row.accessToken.isNullOrBlank() -> "ACCESS_TOKEN_MISSING"
		row.scope.isNullOrBlank() -> "GITHUB_SCOPE_MISSING"
		legacyHasDifferentGitHubAccount(row) -> "MULTIPLE_GITHUB_ACCOUNTS"
		else -> null
	}

	private fun legacyHasDifferentGitHubAccount(row: LegacyCredentialRow): Boolean {
		val plotUserId = row.plotUserId ?: return false
		val accountId = row.githubAccountId?.toString() ?: return false
		val count = sql.queryForObject(
			"""
			select count(*)
			from auth_account a
			join users u on u.auth_subject = a.user_id
			where u.id = ? and a.provider_id = 'github' and a.account_id <> ?
			""".trimIndent(),
			Int::class.java,
			plotUserId,
			accountId,
		) ?: 0
		return count > 0
	}

	private fun quarantine(row: LegacyCredentialRow, reason: String) {
		val now = Timestamp.from(Instant.now())
		sql.update(
			"""
			insert into github_product_credential_quarantine
			(source_account_id, source_user_id, plot_user_id, reason, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?)
			on conflict (source_account_id) do update set reason = excluded.reason,
			  plot_user_id = excluded.plot_user_id, updated_at = excluded.updated_at
			""".trimIndent(),
			row.sourceAccountId, row.sourceUserId, row.plotUserId, reason, now, now,
		)
	}

	private fun advanceCheckpoint(
		lastSourceId: String?,
		processedDelta: Long,
		migratedDelta: Long,
		quarantinedDelta: Long,
		state: String,
	) {
		transactionExecutor.execute {
			sql.update(
				"""
				update github_product_credential_backfill
				set last_source_id = ?, state = ?,
				    processed_count = processed_count + ?,
				    migrated_count = migrated_count + ?,
				    quarantined_count = quarantined_count + ?, updated_at = ?
				where id = 1
				""".trimIndent(),
				lastSourceId, state, processedDelta, migratedDelta, quarantinedDelta, Timestamp.from(Instant.now()),
			)
		}
	}

	private fun readCheckpoint(): BackfillCheckpoint = sql.queryForObject(
		"""
		select last_source_id, state, processed_count, migrated_count, quarantined_count
		from github_product_credential_backfill where id = 1
		""".trimIndent(),
		{ row, _ ->
			BackfillCheckpoint(
				lastSourceId = row.getString("last_source_id"),
				state = requireNotNull(row.getString("state")),
				processedCount = row.getLong("processed_count"),
				migratedCount = row.getLong("migrated_count"),
				quarantinedCount = row.getLong("quarantined_count"),
			)
		},
	) ?: error("GitHub credential backfill checkpoint is missing")

	private fun toLegacyRow(row: com.plot.api.persistence.SqlRow, index: Int) = LegacyCredentialRow(
		sourceAccountId = requireNotNull(row.getString("source_account_id")),
		sourceUserId = row.getString("source_user_id"),
		githubAccountId = row.getString("account_id")?.toLongOrNull(),
		accessToken = row.getString("access_token"),
		refreshToken = row.getString("refresh_token"),
		scope = row.getString("scope"),
		createdAt = requireNotNull(row.getTimestamp("created_at")).toInstant(),
		updatedAt = requireNotNull(row.getTimestamp("updated_at")).toInstant(),
		plotUserId = row.getObject("plot_user_id", UUID::class.java),
	)

	private fun BackfillCheckpoint.toReport() = GitHubCredentialBackfillReport(
		state = state,
		processedCount = processedCount,
		migratedCount = migratedCount,
		quarantinedCount = quarantinedCount,
		checkpoint = lastSourceId,
	)

	private data class BackfillCheckpoint(
		val lastSourceId: String?,
		val state: String,
		val processedCount: Long,
		val migratedCount: Long,
		val quarantinedCount: Long,
	)

	private data class LegacyCredentialRow(
		val sourceAccountId: String,
		val sourceUserId: String?,
		val githubAccountId: Long?,
		val accessToken: String?,
		val refreshToken: String?,
		val scope: String?,
		val createdAt: Instant,
		val updatedAt: Instant,
		val plotUserId: UUID?,
	)
}

private fun Throwable.safeCode(): String = when (this) {
	is GitHubCredentialEncryptionException -> "CREDENTIAL_ENCRYPTION_UNAVAILABLE"
	else -> "GITHUB_CREDENTIAL_BACKFILL_FAILED"
}
