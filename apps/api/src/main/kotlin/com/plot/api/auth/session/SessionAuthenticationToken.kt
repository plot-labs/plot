package com.plot.api.auth.session

import com.plot.api.auth.persistence.AuthUserRecord
import org.springframework.security.authentication.AbstractAuthenticationToken

class SessionAuthenticationToken(
	val authenticatedSession: AuthenticatedSession,
) : AbstractAuthenticationToken(emptyList()) {
	init {
		isAuthenticated = true
	}

	override fun getCredentials(): Any = authenticatedSession.session.token

	override fun getPrincipal(): AuthUserRecord = authenticatedSession.user
}
