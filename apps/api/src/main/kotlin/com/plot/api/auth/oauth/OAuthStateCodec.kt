package com.plot.api.auth.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.plot.api.common.UuidGenerator
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.springframework.stereotype.Component

data class OAuthState(
	val nonce: String,
	val redirectPath: String,
	val expiresAtEpochSeconds: Long,
)

@Component
class OAuthStateCodec(
	private val objectMapper: ObjectMapper,
	private val uuidGenerator: UuidGenerator,
) {
	fun encode(redirectPath: String): String {
		val normalized = normalizeRedirectPath(redirectPath)
		val state = OAuthState(
			nonce = uuidGenerator.next().toString(),
			redirectPath = normalized,
			expiresAtEpochSeconds = Instant.now().plusSeconds(900).epochSecond,
		)
		val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
			objectMapper.writeValueAsBytes(state),
		)
		val signature = sign(payload)
		return "$payload.$signature"
	}

	fun decode(value: String): String {
		val parts = value.split('.', limit = 2)
		require(parts.size == 2) { "Invalid OAuth state" }
		val payload = parts[0]
		require(sign(payload) == parts[1]) { "Invalid OAuth state signature" }
		val state = objectMapper.readValue(Base64.getUrlDecoder().decode(payload), OAuthState::class.java)
		require(state.expiresAtEpochSeconds >= Instant.now().epochSecond) { "OAuth state expired" }
		return normalizeRedirectPath(state.redirectPath)
	}

	private fun normalizeRedirectPath(path: String): String {
		val trimmed = path.trim().ifBlank { DEFAULT_REDIRECT }
		require(trimmed.startsWith('/')) { "Redirect path must be absolute" }
		require(!trimmed.startsWith("//")) { "Redirect path must not be protocol-relative" }
		require(!trimmed.contains("://")) { "Redirect path must be relative" }
		return trimmed
	}

	private fun sign(payload: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
	}

	private companion object {
		const val DEFAULT_REDIRECT = "/auth/complete"
	}
}
