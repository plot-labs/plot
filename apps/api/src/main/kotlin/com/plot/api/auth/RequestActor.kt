package com.plot.api.auth

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
	val authIssuer: String,
	val authSubject: String,
	val email: String,
	val displayName: String,
)

data class WorkspaceActor(
	val actor: RequestActor,
	val workspaceId: UUID,
	val role: String,
	val membership: WorkspaceMember,
)

/** Resolves the authenticated principal and the workspace selected by the BFF header. */
@Component
class RequestActorResolver(
	private val userRepository: UserRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val properties: PlotAuthProperties,
) {
	fun current(): RequestActor? {
		val authentication = SecurityContextHolder.getContext().authentication
		if (authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) return null
		val jwt = (authentication as? JwtAuthenticationToken)?.token ?: return null
		val subject = jwt.subject?.takeIf { it.isNotBlank() } ?: throw unauthorized()
		val issuer = jwt.issuer?.toString()?.takeIf { it.isNotBlank() } ?: properties.issuer
		val user = userRepository.findByAuthIssuerAndAuthSubject(issuer, subject) ?: throw unauthorized()
		if (user.status != "ACTIVE") throw unauthorized()
		return RequestActor(user.id, issuer, subject, user.email, user.displayName)
	}

	fun requireActor(): RequestActor = current() ?: throw unauthorized()

	fun currentWorkspace(): WorkspaceActor? {
		val actor = current() ?: return null
		val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
		val header = request?.getHeader(WORKSPACE_HEADER)?.trim().orEmpty()
		if (header.isBlank()) throw ApiException(HttpStatus.BAD_REQUEST, "WORKSPACE_REQUIRED", "Workspace header is required")
		val workspaceId = if (UUID_PATTERN.matches(header)) runCatching { UUID.fromString(header) }.getOrNull() else null
		if (workspaceId == null) throw ApiException(HttpStatus.BAD_REQUEST, "WORKSPACE_INVALID", "Workspace header is invalid")
		val membership = memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actor.userId, "ACTIVE")
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		return WorkspaceActor(actor, workspaceId, membership.role, membership)
	}

	fun requireWorkspace(): WorkspaceActor = currentWorkspace() ?: throw unauthorized()

	private fun unauthorized(): ApiException = ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required")

	companion object {
		const val WORKSPACE_HEADER = "X-Plot-Workspace-Id"
		private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
	}
}
