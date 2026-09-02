package com.plot.api.auth.session

import com.plot.api.auth.PlotAuthProperties
import com.plot.api.auth.persistence.AuthSessionRecord
import com.plot.api.auth.persistence.AuthSessionRepository
import com.plot.api.auth.persistence.AuthUserRecord
import com.plot.api.auth.persistence.AuthUserRepository
import com.plot.api.common.UuidGenerator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Service

data class AuthenticatedSession(
	val session: AuthSessionRecord,
	val user: AuthUserRecord,
)

@Service
class AuthSessionService(
	private val sessionRepository: AuthSessionRepository,
	private val userRepository: AuthUserRepository,
	private val authProperties: PlotAuthProperties,
	private val uuidGenerator: UuidGenerator,
	private val environment: Environment,
) {
	private val secureRandom = SecureRandom()

	fun createSession(user: AuthUserRecord, request: HttpServletRequest): AuthenticatedSession {
		val now = Instant.now()
		val token = generateToken()
		val session = sessionRepository.save(AuthSessionRecord(
			id = uuidGenerator.next().toString(),
			token = token,
			userId = user.id,
			expiresAt = now.plus(authProperties.sessionTtl),
			createdAt = now,
			updatedAt = now,
			ipAddress = request.remoteAddr,
			userAgent = request.getHeader(HttpHeaders.USER_AGENT),
		))
		return AuthenticatedSession(session, user)
	}

	fun resolveSession(request: HttpServletRequest): AuthenticatedSession? {
		val token = readSessionToken(request) ?: return null
		val session = sessionRepository.findByToken(token) ?: return null
		if (session.expiresAt.isBefore(Instant.now())) {
			sessionRepository.deleteByToken(token)
			return null
		}
		val user = userRepository.findById(session.userId) ?: return null
		return AuthenticatedSession(session, user)
	}

	fun revokeSession(request: HttpServletRequest): Boolean {
		val token = readSessionToken(request) ?: return false
		return sessionRepository.deleteByToken(token) > 0
	}

	fun writeSessionCookie(response: HttpServletResponse, token: String) {
		val cookie = sessionCookie(token, maxAgeSeconds = authProperties.sessionTtl.seconds)
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
	}

	fun clearSessionCookie(response: HttpServletResponse) {
		val cookie = sessionCookie("", maxAgeSeconds = 0)
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
	}

	fun readSessionToken(request: HttpServletRequest): String? {
		val header = request.getHeader(HttpHeaders.COOKIE) ?: return null
		return header.split(';')
			.map { it.trim() }
			.firstOrNull { it.startsWith("${authProperties.sessionCookieName}=") }
			?.substringAfter('=')
			?.takeIf { it.isNotBlank() }
	}

	private fun sessionCookie(value: String, maxAgeSeconds: Long): ResponseCookie {
		val builder = ResponseCookie.from(authProperties.sessionCookieName, value)
			.httpOnly(true)
			.path("/")
			.sameSite("Lax")
			.maxAge(maxAgeSeconds)
		if (isProduction()) builder.secure(true)
		authProperties.cookieDomain?.takeIf { it.isNotBlank() }?.let { builder.domain(it) }
		return builder.build()
	}

	private fun generateToken(): String {
		val bytes = ByteArray(32)
		secureRandom.nextBytes(bytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	private fun isProduction(): Boolean = environment.activeProfiles.none { it in setOf("local", "test") }
}
