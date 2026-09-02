package com.plot.api.auth.oauth

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

@Component
class OAuthStateNonceStore {
	private val consumedNonces = ConcurrentHashMap<String, Instant>()

	fun consume(nonce: String, expiresAt: Instant) {
		pruneExpired()
		require(consumedNonces.putIfAbsent(nonce, expiresAt) == null) { "OAuth state already used" }
	}

	private fun pruneExpired() {
		val now = Instant.now()
		consumedNonces.entries.removeIf { it.value.isBefore(now) }
	}
}
