package com.plot.api.github

import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

@Repository
class GitHubProductCredentialRepository(
	private val sql: SqlExecutor,
	private val cipher: GitHubProductCredentialCipher,
) {
	fun findActiveByUserId(userId: UUID): GitHubProductCredential? = sql.queryForObject(
		ACTIVE_BY_USER,
		::toModel,
		userId,
	)

	fun saveActive(credential: GitHubProductCredential): GitHubProductCredential {
		val accessCiphertext = cipher.encrypt(credential.accessToken, credential.encryptionKeyVersion)
		val refreshCiphertext = credential.refreshToken?.takeIf { it.isNotBlank() }
			?.let { cipher.encrypt(it, credential.encryptionKeyVersion) }
		val now = credential.updatedAt
		sql.update(
			"""
			update github_product_credentials
			set status = 'RETIRED', revoked_at = ?, updated_at = ?
			where user_id = ? and status = 'ACTIVE' and github_account_id <> ?
			""".trimIndent(),
			Timestamp.from(now), Timestamp.from(now), credential.userId, credential.githubAccountId,
		)
		sql.queryForObject(
			"""
			insert into github_product_credentials (
			  id, user_id, github_account_id, github_login, access_token_ciphertext,
			  refresh_token_ciphertext, scope, status, revoked_at, encryption_key_version,
			  created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', null, ?, ?, ?)
			on conflict (user_id, github_account_id) do update set
			  github_login = excluded.github_login,
			  access_token_ciphertext = excluded.access_token_ciphertext,
			  refresh_token_ciphertext = excluded.refresh_token_ciphertext,
			  scope = excluded.scope,
			  status = 'ACTIVE',
			  revoked_at = null,
			  encryption_key_version = excluded.encryption_key_version,
			  updated_at = excluded.updated_at
			returning id
			""".trimIndent(),
			UUID::class.java,
			credential.id,
			credential.userId,
			credential.githubAccountId,
			credential.githubLogin,
			accessCiphertext,
			refreshCiphertext,
			credential.scope,
			credential.encryptionKeyVersion,
			Timestamp.from(credential.createdAt),
			Timestamp.from(now),
		) ?: throw IllegalStateException("GitHub product credential could not be saved")
		return findActiveByUserId(credential.userId)
			?: throw IllegalStateException("GitHub product credential could not be read after save")
	}

	fun retireActiveForUser(userId: UUID, now: Instant): Int = sql.update(
		"""
		update github_product_credentials
		set status = 'RETIRED', revoked_at = ?, updated_at = ?
		where user_id = ? and status = 'ACTIVE'
		""".trimIndent(),
		Timestamp.from(now), Timestamp.from(now), userId,
	)

	private fun toModel(row: com.plot.api.persistence.SqlRow, index: Int): GitHubProductCredential {
		val keyVersion = requireNotNull(row.getString("encryption_key_version"))
		return GitHubProductCredential(
			id = requireNotNull(row.getObject("id", UUID::class.java)),
			userId = requireNotNull(row.getObject("user_id", UUID::class.java)),
			githubAccountId = row.getLong("github_account_id"),
			githubLogin = row.getString("github_login"),
			accessToken = cipher.decrypt(requireNotNull(row.getString("access_token_ciphertext")), keyVersion),
			refreshToken = row.getString("refresh_token_ciphertext")?.let { cipher.decrypt(it, keyVersion) },
			scope = requireNotNull(row.getString("scope")),
			status = requireNotNull(row.getString("status")),
			createdAt = requireNotNull(row.getTimestamp("created_at")).toInstant(),
			updatedAt = requireNotNull(row.getTimestamp("updated_at")).toInstant(),
			revokedAt = row.getTimestamp("revoked_at")?.toInstant(),
			encryptionKeyVersion = keyVersion,
		)
	}

	private companion object {
		const val ACTIVE_BY_USER = """
			select id, user_id, github_account_id, github_login, access_token_ciphertext,
			       refresh_token_ciphertext, scope, status, revoked_at, encryption_key_version,
			       created_at, updated_at
			from github_product_credentials
			where user_id = ? and status = 'ACTIVE'
			order by updated_at desc, id desc
			limit 1
			"""
	}
}
