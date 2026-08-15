package com.plot.api.github

import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.persistence.JooqSqlExecutor
import java.sql.Timestamp
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class GitHubInstallationState(
	val id: UUID,
	val value: String,
	val expiresAt: Instant,
)

data class GitHubInstallationStateBinding(
	val userId: UUID,
	val workspaceId: UUID,
)

@Service
class GitHubInstallationStateService(
	private val properties: GitHubProperties,
	private val devContext: DevContext,
	private val sqlExecutor: JooqSqlExecutor,
	private val clock: Clock = Clock.systemUTC(),
	private val random: SecureRandom = SecureRandom(),
) {
	@Transactional
	fun create(): GitHubInstallationState {
		val nonceBytes = ByteArray(32)
		random.nextBytes(nonceBytes)
		val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
		val payload = listOf(devContext.devUserId, devContext.devWorkspaceId, nonce).joinToString(".")
		val signature = sign(payload)
		val now = Instant.now(clock)
		val expiresAt = now.plusSeconds(properties.stateTtlSeconds)
		val id = UUID.randomUUID()
		sqlExecutor.update(
			"""
			insert into github_installation_states
			(id, workspace_id, user_id, nonce_hash, expires_at, created_at)
			values (?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			devContext.devUserId,
			hash(nonce),
			Timestamp.from(expiresAt),
			Timestamp.from(now),
		)
		return GitHubInstallationState(id, "$payload.$signature", expiresAt)
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
	fun consume(value: String): GitHubInstallationStateBinding {
		val parts = value.split('.')
		if (parts.size != 4) invalid()
		val payload = parts.dropLast(1).joinToString(".")
		val expected = sign(payload)
		if (!MessageDigest.isEqual(expected.toByteArray(), parts.last().toByteArray())) invalid()
		val userId = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: invalid()
		val workspaceId = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: invalid()
		val nonce = parts[2]
		val now = Instant.now(clock)
		val updated = sqlExecutor.update(
			"""
			update github_installation_states
			set consumed_at = ?
			where workspace_id = ? and user_id = ? and nonce_hash = ?
			  and consumed_at is null and expires_at > ?
			""".trimIndent(),
			Timestamp.from(now),
			workspaceId,
			userId,
			hash(nonce),
			Timestamp.from(now),
		)
		if (updated != 1) invalid()
		return GitHubInstallationStateBinding(userId, workspaceId)
	}

	private fun sign(payload: String): String {
		val secret = properties.stateSecret?.takeIf { it.isNotBlank() }
			?: throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_NOT_CONFIGURED", "GitHub is not configured")
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
		return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
	}

	private fun hash(value: String): String = Base64.getEncoder().encodeToString(
		MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
	)

	private fun invalid(): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_GITHUB_STATE", "GitHub installation state is invalid")
}
