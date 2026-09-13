package com.plot.api.auth

import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties

/** API-side WorkOS verification, provisioning, and membership configuration. */
@ConfigurationProperties("plot.workos")
data class WorkOSAuthProperties(
	val enabled: Boolean = false,
	val apiKey: String = "",
	val clientId: String = "",
	val issuer: String = "",
	val audience: String = "",
	val jwksUri: String = "",
	val webhookSecret: String = "",
	val ownerRoleSlug: String = "owner",
	val bootstrapEnabled: Boolean = true,
) {
	init {
		require(ownerRoleSlug.matches(SAFE_ROLE_SLUG)) {
			"plot.workos.owner-role-slug must contain only lowercase letters, numbers, hyphens, or underscores"
		}

		if (enabled) {
			require(apiKey.isNotBlank()) { "plot.workos.api-key is required when WorkOS auth is enabled" }
			require(clientId.isNotBlank()) { "plot.workos.client-id is required when WorkOS auth is enabled" }
			require(audience.isNotBlank()) { "plot.workos.audience is required when WorkOS auth is enabled" }
			requireValidHttpsOrLoopback("plot.workos.issuer", issuer)
			requireValidHttpsOrLoopback("plot.workos.jwks-uri", jwksUri)
		}
	}

	private companion object {
		val SAFE_ROLE_SLUG = Regex("^[a-z0-9_-]+$")

		fun requireValidHttpsOrLoopback(name: String, value: String) {
			val uri = runCatching { URI(value.trim()) }.getOrNull()
			require(uri != null && uri.isAbsolute && uri.userInfo == null && uri.query == null && uri.fragment == null) {
				name + " must be an absolute http(s) origin or URL"
			}
			require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
				name + " must use an http(s) URL with a host"
			}
			if (uri.scheme == "http") {
				require(uri.host == "localhost" || uri.host == "127.0.0.1" || uri.host == "::1") {
					name + " may use http only on loopback hosts"
				}
			}
		}
	}
}
