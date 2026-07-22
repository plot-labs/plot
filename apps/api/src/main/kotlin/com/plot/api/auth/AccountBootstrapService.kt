package com.plot.api.auth

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.workspace.User
import com.plot.api.workspace.UserRepository
import com.plot.api.workspace.Workspace
import com.plot.api.workspace.WorkspaceMember
import com.plot.api.workspace.WorkspaceMemberRepository
import com.plot.api.workspace.WorkspaceRepository
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager

data class BootstrapAccountResponse(
	val userId: UUID,
	val workspaceId: UUID,
	val created: Boolean,
)

@Service
class AccountBootstrapService(
	private val userRepository: UserRepository,
	private val workspaceRepository: WorkspaceRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val uuidGenerator: UuidGenerator,
	private val authProperties: PlotAuthProperties,
	private val transactionManager: PlatformTransactionManager,
) {
	fun bootstrap(jwt: Jwt): BootstrapAccountResponse {
		val subject = jwt.subject?.takeIf { it.isNotBlank() } ?: throw unauthorized()
		val issuer = jwt.issuer?.toString()?.takeIf { it.isNotBlank() } ?: authProperties.issuer
		val email = jwt.getClaimAsString("email")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
			?: throw ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied")
		val displayName = jwt.getClaimAsString("name")?.trim()?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')

		userRepository.findByAuthIssuerAndAuthSubject(issuer, subject)?.let { user ->
			if (user.status != "ACTIVE") throw unauthorized()
			return BootstrapAccountResponse(user.id, findDefaultWorkspace(user.id), false)
		}
		userRepository.findByEmailIgnoreCase(email)?.let {
			throw ApiException(HttpStatus.CONFLICT, "ACCOUNT_LINK_REQUIRED", "This email is linked to an existing Plot account")
		}

		val now = Instant.now()
		val userId = uuidGenerator.next()
		val workspaceId = uuidGenerator.next()
		val memberId = uuidGenerator.next()
		return try {
			TransactionTemplate(transactionManager).execute {
				val user = userRepository.saveAndFlush(User(
					id = userId,
					email = email,
					displayName = displayName,
					status = "ACTIVE",
					authIssuer = issuer,
					authSubject = subject,
					createdAt = now,
					updatedAt = now,
				))
				workspaceRepository.saveAndFlush(Workspace(
					id = workspaceId,
					name = "Personal",
					slug = "personal-${userId.toString().take(8)}",
					createdByUserId = userId,
					status = "ACTIVE",
					createdAt = now,
					updatedAt = now,
				))
				memberRepository.save(WorkspaceMember(
					id = memberId,
					workspaceId = workspaceId,
					userId = userId,
					role = "OWNER",
					status = "ACTIVE",
					joinedAt = now,
					createdAt = now,
					updatedAt = now,
				))
				BootstrapAccountResponse(user.id, workspaceId, true)
			}
		} catch (exception: DataIntegrityViolationException) {
			// Concurrent first requests race on the identity unique index; the winner is authoritative.
			userRepository.findByAuthIssuerAndAuthSubject(issuer, subject)?.let { user ->
				BootstrapAccountResponse(user.id, findDefaultWorkspace(user.id), false)
			} ?: if (userRepository.findByEmailIgnoreCase(email) != null) {
				throw ApiException(HttpStatus.CONFLICT, "ACCOUNT_LINK_REQUIRED", "This email is linked to an existing Plot account")
			} else {
				throw exception
			}
		}
	}

	private fun findDefaultWorkspace(userId: UUID): UUID = memberRepository
		.findAllByUserIdAndStatusOrderByCreatedAtAsc(userId, "ACTIVE")
		.firstOrNull()?.workspaceId
		?: throw ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied")

	private fun unauthorized(): ApiException = ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required")
}
