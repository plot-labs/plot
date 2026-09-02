package com.plot.api.auth.oauth

import tools.jackson.databind.ObjectMapper
import com.plot.api.auth.PlotAuthProperties
import com.plot.api.common.UuidGenerator
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
	private val authProperties: PlotAuthProperties,
	private val oauthStateNonceStore: OAuthStateNonceStore,
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
		val expiresAt = Instant.ofEpochSecond(state.expiresAtEpochSeconds)
		require(expiresAt >= Instant.now()) { "OAuth state expired" }
		oauthStateNonceStore.consume(state.nonce, expiresAt)
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
		val secret = authProperties.githubClientSecret.takeIf { it.isNotBlank() }
			?: error("plot.auth.github-client-secret is required for OAuth state signing")
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
		val digest = mac.doFinal(payload.toByteArray())
		return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
	}

	private companion object {
		const val DEFAULT_REDIRECT = "/auth/complete"
	}
}
