package com.plot.api.github

import com.plot.api.auth.RequestActorResolver
import com.plot.api.common.ApiException
import org.springframework.http.HttpStatus
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Keeps the optional integration inert until it is explicitly enabled.  The
 * fixed DevContext is a tenant filter, not an authentication boundary, so a
 * production deployment must provide its own auth gate before enabling this.
 */
@Component
class GitHubGuard(
	private val properties: GitHubProperties,
	private val environment: Environment,
	private val actorResolver: RequestActorResolver? = null,
) {
	fun requireReadAccess() {
		requireEnabled()
	}

	fun requireEnabled() {
		if (
			!properties.enabled || properties.appId.isNullOrBlank() || properties.appSlug.isNullOrBlank() ||
			properties.privateKey.isNullOrBlank() || properties.stateSecret.isNullOrBlank() ||
			properties.stateTtlSeconds !in 1..900 || properties.importPageCap < 1 || properties.repositoryPageCap < 1
		) {
			throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"GITHUB_NOT_CONFIGURED",
				"GitHub is not configured",
			)
		}
		requirePinnedOrigins()
		if (actorResolver?.current() != null) return
		if (!properties.devOnly) {
			throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"GITHUB_AUTH_REQUIRED",
				"GitHub routes require product authentication before production exposure",
			)
		}
		if (environment.activeProfiles.none { it == "local" || it == "dev" || it == "test" }) {
			throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"GITHUB_DEV_PROFILE_REQUIRED",
				"GitHub development routes require an explicit local profile",
			)
		}
		if (!properties.loopbackOnly) {
			throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"GITHUB_DEV_EXPOSURE_INVALID",
				"GitHub development exposure must be loopback-only",
			)
		}
		val serverAddress = environment.getProperty("server.address")
		if (serverAddress !in setOf("localhost", "127.0.0.1", "::1")) {
			throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"GITHUB_DEV_EXPOSURE_INVALID",
				"GitHub development routes require a loopback server address",
			)
		}
	}

	/**
	 * Installation tokens and App JWTs ride the Authorization header, so the
	 * endpoint pin is enforced for every caller regardless of authentication:
	 * a base-url misconfiguration would otherwise leak those credentials to
	 * whatever host was configured.
	 */
	private fun requirePinnedOrigins() {
		if (properties.apiBaseUrl != "https://api.github.com" || properties.webBaseUrl != "https://github.com") {
			throw ApiException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"GITHUB_ORIGIN_INVALID",
				"GitHub endpoints must point at the official GitHub origins",
			)
		}
	}

}
