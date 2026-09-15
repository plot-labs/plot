package com.plot.api.github

import com.plot.api.common.ApiException
import com.plot.api.persistence.SqlExecutor
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class GitHubProductOAuthState(
	val value: String,
	val expiresAt: Instant,
)

data class GitHubProductOAuthStateBinding(
	val userId: UUID,
	val workspaceId: UUID,
	val returnPath: String,
)

/** One-time, database-backed state for the product GitHub OAuth flow. */
@Service
class GitHubProductOAuthStateService(
	private val properties: GitHubProperties,
	private val sql: SqlExecutor,
	private val clock: Clock = Clock.systemUTC(),
	private val random: SecureRandom = SecureRandom(),
) {
	@Transactional
	fun create(userId: UUID, workspaceId: UUID, returnPath: String): GitHubProductOAuthState {
		val stateBytes = ByteArray(STATE_BYTES).also(random::nextBytes)
		val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
		val now = Instant.now(clock)
		val expiresAt = now.plusSeconds(properties.stateTtlSeconds)
		sql.update(
			"""
			insert into github_product_oauth_states
			(state_hash, user_id, workspace_id, return_path, expires_at, created_at)
			values (?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			hash(state), userId, workspaceId, normalizeReturnPath(returnPath),
			Timestamp.from(expiresAt), Timestamp.from(now),
		)
		return GitHubProductOAuthState(state, expiresAt)
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun consume(value: String): GitHubProductOAuthStateBinding {
		if (value.length !in 32..256) invalid()
		val now = Instant.now(clock)
		val stateHash = hash(value)
		val updated = sql.update(
			"""
			update github_product_oauth_states
			set consumed_at = ?
			where state_hash = ? and consumed_at is null and expires_at > ?
			""".trimIndent(),
			Timestamp.from(now), stateHash, Timestamp.from(now),
		)
		if (updated != 1) invalid()
		return sql.queryForObject(BINDING_BY_HASH, ::toBinding, stateHash) ?: invalid()
	}

	private fun normalizeReturnPath(value: String): String = when (value.trim()) {
		"/settings/integrations", "/chat" -> value.trim()
		else -> "/settings/integrations"
	}

	private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(StandardCharsets.UTF_8))
		.joinToString("") { byte -> "%02x".format(byte) }

	private fun toBinding(row: com.plot.api.persistence.SqlRow, index: Int) = GitHubProductOAuthStateBinding(
		userId = requireNotNull(row.getObject("user_id", UUID::class.java)),
		workspaceId = requireNotNull(row.getObject("workspace_id", UUID::class.java)),
		returnPath = requireNotNull(row.getString("return_path")),
	)

	private fun invalid(): Nothing = throw ApiException(
		HttpStatus.BAD_REQUEST,
		"INVALID_GITHUB_PRODUCT_OAUTH_STATE",
		"GitHub authorization state is invalid",
	)

	private companion object {
		const val STATE_BYTES = 32
		const val BINDING_BY_HASH = """
			select user_id, workspace_id, return_path
			from github_product_oauth_states where state_hash = ?
			"""
	}
}
