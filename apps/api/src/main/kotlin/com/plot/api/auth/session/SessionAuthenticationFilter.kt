package com.plot.api.auth.session

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class SessionAuthenticationFilter(
	private val authSessionService: AuthSessionService,
) : OncePerRequestFilter() {
	override fun shouldNotFilter(request: HttpServletRequest): Boolean {
		val path = request.servletPath
		return path != "/api/auth/session" && path != "/api/auth/token" && path != "/api/auth/sign-out"
	}

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val context = SecurityContextHolder.getContext()
		if (context.authentication?.isAuthenticated != true) {
			authSessionService.resolveSession(request)?.let { session ->
				context.authentication = SessionAuthenticationToken(session)
			}
		}
		filterChain.doFilter(request, response)
	}
}
