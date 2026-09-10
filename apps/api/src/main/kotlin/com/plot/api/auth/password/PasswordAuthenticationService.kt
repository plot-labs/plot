package com.plot.api.auth.password

import com.plot.api.auth.persistence.AuthAccountRecord
import com.plot.api.auth.persistence.AuthAccountRepository
import com.plot.api.auth.persistence.AuthUserRecord
import com.plot.api.auth.persistence.AuthUserRepository
import com.plot.api.auth.policy.AllowedEmailPolicy
import com.plot.api.auth.session.AuthSessionService
import com.plot.api.auth.session.AuthenticatedSession
import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.JooqTransactionExecutor
import com.plot.api.workspace.UserRepository as WorkspaceUserRepository
import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class PasswordAuthenticationService(
	private val allowedEmailPolicy: AllowedEmailPolicy,
	private val authUserRepository: AuthUserRepository,
	private val authAccountRepository: AuthAccountRepository,
	private val workspaceUserRepository: WorkspaceUserRepository,
	private val authSessionService: AuthSessionService,
	private val transactionExecutor: JooqTransactionExecutor,
	private val uuidGenerator: UuidGenerator,
	private val passwordEncoder: PasswordEncoder,
) {
	private val dummyPasswordHash: String = requireNotNull(passwordEncoder.encode(DUMMY_PASSWORD))

	fun authenticate(email: String, password: String, request: HttpServletRequest): AuthenticatedSession {
		val normalizedEmail = allowedEmailPolicy.normalizeEmail(email)
		val userAndHash = if (allowedEmailPolicy.isAllowed(normalizedEmail)) {
			authUserRepository.findByEmailIgnoreCase(normalizedEmail)?.let { user ->
				authAccountRepository.findByUserIdAndProviderId(user.id, CREDENTIAL_PROVIDER_ID)
					?.password
					?.let { hash -> user to hash }
			}
		} else {
			null
		}

		val passwordMatches = userAndHash?.second?.let { hash -> matches(password, hash) }
			?: matches(password, dummyPasswordHash)
		if (!passwordMatches) throw invalidCredentials()

		val user = userAndHash?.first ?: throw invalidCredentials()
		return authSessionService.createSession(user, request)
	}

	fun register(
		email: String,
		password: String,
		name: String?,
		request: HttpServletRequest,
	): AuthenticatedSession {
		val normalizedEmail = allowedEmailPolicy.normalizeEmail(email)
		if (!allowedEmailPolicy.isAllowed(normalizedEmail)) throw signUpNotAllowed()
		if (isRegistered(normalizedEmail)) throw emailAlreadyRegistered()

		val now = Instant.now()
		val user = try {
			transactionExecutor.execute {
				if (isRegistered(normalizedEmail)) throw emailAlreadyRegistered()
				val createdUser = authUserRepository.save(AuthUserRecord(
					id = uuidGenerator.next().toString(),
					name = name?.trim()?.takeIf { it.isNotEmpty() } ?: normalizedEmail.substringBefore('@'),
					email = normalizedEmail,
					emailVerified = true,
					image = null,
					createdAt = now,
					updatedAt = now,
				))
				authAccountRepository.save(AuthAccountRecord(
					id = uuidGenerator.next().toString(),
					accountId = normalizedEmail,
					providerId = CREDENTIAL_PROVIDER_ID,
					issuer = CREDENTIAL_ISSUER,
					userId = createdUser.id,
					accessToken = null,
					refreshToken = null,
					scope = null,
					password = requireNotNull(passwordEncoder.encode(password)),
					createdAt = now,
					updatedAt = now,
				))
				createdUser
			}
		} catch (exception: DataIntegrityViolationException) {
			if (isRegistered(normalizedEmail)) throw emailAlreadyRegistered()
			throw exception
		}

		return authSessionService.createSession(user, request)
	}

	private fun isRegistered(email: String): Boolean =
		authUserRepository.findByEmailIgnoreCase(email) != null ||
			workspaceUserRepository.findByEmailIgnoreCase(email) != null

	private fun signUpNotAllowed() = ApiException(
		HttpStatus.FORBIDDEN,
		"SIGN_UP_NOT_ALLOWED",
		"Sign-up is limited to approved accounts",
	)

	private fun emailAlreadyRegistered() = ApiException(
		HttpStatus.CONFLICT,
		"EMAIL_ALREADY_REGISTERED",
		"An account with this email already exists",
	)

	private fun matches(password: String, hash: String): Boolean = runCatching {
		passwordEncoder.matches(password, hash)
	}.getOrDefault(false)

	private fun invalidCredentials() = ApiException(
		HttpStatus.UNAUTHORIZED,
		"INVALID_CREDENTIALS",
		"Invalid email or password",
	)

	private companion object {
		const val CREDENTIAL_PROVIDER_ID = "credential"
		const val CREDENTIAL_ISSUER = "local:password"
		const val DUMMY_PASSWORD = "plot-invalid-password"
	}
}
