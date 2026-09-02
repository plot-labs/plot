package com.plot.api.auth.oauth

import com.plot.api.auth.PlotAuthProperties
import com.plot.api.auth.persistence.AuthAccountRecord
import com.plot.api.auth.persistence.AuthAccountRepository
import com.plot.api.auth.persistence.AuthUserRecord
import com.plot.api.auth.persistence.AuthUserRepository
import com.plot.api.auth.policy.AllowedEmailPolicy
import com.plot.api.auth.session.AuthSessionService
import com.plot.api.auth.session.AuthenticatedSession
import com.plot.api.common.UuidGenerator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant
import org.springframework.stereotype.Service

@Service
class AuthOAuthService(
	private val githubOAuthService: GitHubOAuthService,
	private val allowedEmailPolicy: AllowedEmailPolicy,
	private val authUserRepository: AuthUserRepository,
	private val authAccountRepository: AuthAccountRepository,
	private val authSessionService: AuthSessionService,
	private val uuidGenerator: UuidGenerator,
) {
	fun completeGitHubLogin(
		code: String,
		request: HttpServletRequest,
		response: HttpServletResponse,
	): AuthenticatedSession {
		val accessToken = githubOAuthService.exchangeCode(code)
		val profile = githubOAuthService.fetchProfile(accessToken)
		val email = allowedEmailPolicy.normalizeEmail(
			profile.email ?: throw AllowedEmailPolicy.AccessDeniedException(),
		)
		allowedEmailPolicy.assertAllowed(email)

		val now = Instant.now()
		val existingAccount = authAccountRepository.findByIssuerAndAccountId(GITHUB_ISSUER, profile.id)
		val user = when {
			existingAccount != null -> authUserRepository.findById(existingAccount.userId)
				?.let { updateUser(it, profile, email, now) }
				?: throw IllegalStateException("Linked auth user is missing")
			else -> createUser(profile, email, now)
		}
		authAccountRepository.save(buildAccount(existingAccount, profile, user, accessToken, now))
		val authenticated = authSessionService.createSession(user, request)
		authSessionService.writeSessionCookie(response, authenticated.session.token)
		return authenticated
	}

	private fun createUser(profile: GitHubProfile, email: String, now: Instant): AuthUserRecord = authUserRepository.save(
		AuthUserRecord(
			id = uuidGenerator.next().toString(),
			name = profile.name?.takeIf { it.isNotBlank() } ?: profile.login,
			email = email,
			emailVerified = true,
			image = profile.avatarUrl,
			createdAt = now,
			updatedAt = now,
		),
	)

	private fun updateUser(
		user: AuthUserRecord,
		profile: GitHubProfile,
		email: String,
		now: Instant,
	): AuthUserRecord = authUserRepository.save(user.copy(
		name = profile.name?.takeIf { it.isNotBlank() } ?: profile.login,
		email = email,
		emailVerified = true,
		image = profile.avatarUrl,
		updatedAt = now,
	))

	private fun buildAccount(
		existing: AuthAccountRecord?,
		profile: GitHubProfile,
		user: AuthUserRecord,
		accessToken: String,
		now: Instant,
	): AuthAccountRecord = AuthAccountRecord(
		id = existing?.id ?: uuidGenerator.next().toString(),
		accountId = profile.id,
		providerId = GITHUB_PROVIDER,
		issuer = GITHUB_ISSUER,
		userId = user.id,
		accessToken = accessToken,
		refreshToken = existing?.refreshToken,
		scope = "read:user user:email",
		createdAt = existing?.createdAt ?: now,
		updatedAt = now,
	)

	private companion object {
		const val GITHUB_ISSUER = "local:oauth:github"
		const val GITHUB_PROVIDER = "github"
	}
}
