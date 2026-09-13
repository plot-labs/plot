package com.plot.api.auth

import com.plot.api.auth.workos.WorkOSIdentityMappingRepository
import com.plot.api.auth.workos.WorkOSMembershipProjectionService
import com.plot.api.auth.workos.WorkOSOrganizationMappingRepository
import com.plot.api.auth.workos.normalizeWorkOSRole
import com.plot.api.common.ApiException
import com.plot.api.workspace.UserRepository
import com.plot.api.workspace.WorkspaceMember
import com.plot.api.workspace.WorkspaceMemberRepository
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
data class RequestActor(
	val userId: UUID,
	val workOSUserId: String,
	val email: String,
	val displayName: String,
)

data class WorkspaceActor(
	val actor: RequestActor,
	val workspaceId: UUID,
	val role: String,
	val membership: WorkspaceMember,
)

/** Resolves the authenticated principal and the Workspace in the WorkOS token context. */
@Component
class RequestActorResolver(
	private val userRepository: UserRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val properties: WorkOSAuthProperties,
	private val workOSIdentityMappingRepository: WorkOSIdentityMappingRepository,
	private val workOSOrganizationMappingRepository: WorkOSOrganizationMappingRepository,
	private val workOSMembershipProjectionService: WorkOSMembershipProjectionService,
) {
	fun current(): RequestActor? {
		val jwt = authenticatedJwt() ?: return null
		val workOSUserId = jwt.subject?.takeIf { it.isNotBlank() } ?: throw unauthorized()
		val mapping = workOSIdentityMappingRepository.findByWorkOSUserId(workOSUserId) ?: throw unauthorized()
		val user = userRepository.findById(mapping.plotUserId).orElse(null) ?: throw unauthorized()
		if (user.status != "ACTIVE") throw unauthorized()
		return RequestActor(user.id, workOSUserId, user.email, user.displayName)
	}

	fun requireActor(): RequestActor = current() ?: throw unauthorized()

	fun currentWorkOSOrganizationId(): String? = authenticatedJwt()
		?.getClaimAsString("org_id")
		?.trim()
		?.takeIf { it.isNotBlank() }

	fun currentWorkspace(): WorkspaceActor? {
		val actor = current() ?: return null
		val jwt = authenticatedJwt() ?: throw unauthorized()
		return resolveWorkOSWorkspace(actor, jwt)
	}

	fun requireWorkspace(): WorkspaceActor = currentWorkspace() ?: throw unauthorized()

	private fun resolveWorkOSWorkspace(actor: RequestActor, jwt: org.springframework.security.oauth2.jwt.Jwt): WorkspaceActor {
		val organizationId = jwt.getClaimAsString("org_id")?.trim()?.takeIf { it.isNotBlank() }
			?: throw ApiException(
				HttpStatus.FORBIDDEN,
				"WORKSPACE_CONTEXT_REQUIRED",
				"An active Workspace context is required",
			)
		val organization = workOSOrganizationMappingRepository.findByOrganizationId(organizationId)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		val roleSlug = jwt.getClaimAsString("role")?.trim()?.takeIf { it.isNotBlank() }
			?: jwt.getClaimAsStringList("roles")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
		val role = normalizeWorkOSRole(roleSlug, properties.ownerRoleSlug)
			?: throw ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied")
		val projection = workOSMembershipProjectionService.reconcileToken(
			workOSUserId = actor.workOSUserId,
			organizationId = organizationId,
			roleSlug = roleSlug,
		) ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		if (!projection.active) throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")

		val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
		val header = request?.getHeader(WORKSPACE_HEADER)?.trim().orEmpty()
		if (header.isNotBlank()) {
			val requestedWorkspaceId = if (UUID_PATTERN.matches(header)) runCatching { UUID.fromString(header) }.getOrNull() else null
			if (requestedWorkspaceId == null) {
				throw ApiException(HttpStatus.BAD_REQUEST, "WORKSPACE_INVALID", "Workspace header is invalid")
			}
			if (requestedWorkspaceId != organization.workspaceId) {
				throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
			}
		}
		val membership = memberRepository.findByWorkspaceIdAndUserIdAndStatus(
			organization.workspaceId,
			actor.userId,
			"ACTIVE",
		) ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		return WorkspaceActor(actor, organization.workspaceId, role, membership)
	}

	private fun authenticatedJwt(): org.springframework.security.oauth2.jwt.Jwt? {
		val authentication = SecurityContextHolder.getContext().authentication
		if (authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) return null
		return (authentication as? JwtAuthenticationToken)?.token
	}

	private fun unauthorized(): ApiException = ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required")

	companion object {
		const val WORKSPACE_HEADER = "X-Plot-Workspace-Id"
		private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
	}
}
