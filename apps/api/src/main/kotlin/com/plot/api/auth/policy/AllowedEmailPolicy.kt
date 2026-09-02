package com.plot.api.auth.policy

import com.plot.api.auth.PlotAuthProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class AllowedEmailPolicy(
	private val properties: PlotAuthProperties,
	private val environment: Environment,
) {
	init {
		if (isProduction() && properties.allowedEmails.isEmpty()) {
			throw IllegalStateException("AUTH_ALLOWED_EMAILS must contain at least one address in production")
		}
	}

	fun normalizeEmail(value: String): String = value.trim().lowercase()

	fun isAllowed(email: String): Boolean =
		properties.allowedEmails.any { normalizeEmail(it) == normalizeEmail(email) }

	fun assertAllowed(email: String) {
		if (!isAllowed(email)) throw AccessDeniedException()
	}

	private fun isProduction(): Boolean = environment.activeProfiles.none { it in setOf("local", "test") }

	class AccessDeniedException : RuntimeException("Access denied")
}
