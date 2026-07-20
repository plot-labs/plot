package com.plot.api.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Optional deployment guard for local-only operator stacks; disabled in ordinary production. */
@Component
@ConditionalOnProperty(prefix = "plot.loopback-request-guard", name = ["enabled"], havingValue = "true")
class LoopbackRequestGuardFilter : OncePerRequestFilter() {
	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
		val host = request.getHeader("Host")
		val forwarded = request.getHeader("X-Forwarded-Host")
		if (host == null || !isLoopbackAuthority(host) ||
			(forwarded != null && !forwarded.split(',').all { isLoopbackAuthority(it.trim()) })
		) {
			response.sendError(421)
			return
		}
		filterChain.doFilter(request, response)
	}

	private fun isLoopbackAuthority(value: String): Boolean {
		val normalized = value.lowercase()
		if (normalized == "::1") return true
		val match = BRACKETED_IPV6.matchEntire(normalized) ?: IPV4_OR_LOCALHOST.matchEntire(normalized) ?: return false
		val port = match.groups[1]?.value
		return port == null || port.toIntOrNull() in 1..65535
	}

	companion object {
		private val BRACKETED_IPV6 = Regex("^\\[::1](?::([0-9]{1,5}))?$")
		private val IPV4_OR_LOCALHOST = Regex("^(?:127\\.0\\.0\\.1|localhost)(?::([0-9]{1,5}))?$")
	}
}
