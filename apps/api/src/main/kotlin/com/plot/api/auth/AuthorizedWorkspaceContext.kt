package com.plot.api.auth

import com.plot.api.common.ApiException
import com.plot.api.common.allowsDevelopmentAuthBypass
import com.plot.api.workspace.WorkspaceMember
import java.time.Instant
import java.util.UUID
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * One request-scoped authorization boundary for Plot data.
 *
 * A valid WorkOS token is not enough to access tenant data: the token must
 * resolve to a mapped Plot user and an active membership selected by the BFF
 * header. The fixed fallback exists only in local/test profiles and is marked
 * on the returned context so it cannot be mistaken for a real actor.
 */
data class ResolvedWorkspaceContext(
	val actor: RequestActor,
	val workspace: WorkspaceActor,
	val developmentFallback: Boolean,
)

@Component
class AuthorizedWorkspaceContext(
	private val actorResolver: RequestActorResolver,
	private val environment: Environment,
) {
	fun current(): ResolvedWorkspaceContext? {
		val actor = actorResolver.current()
		if (actor != null) {
			val workspace = actorResolver.requireWorkspace()
			return ResolvedWorkspaceContext(actor, workspace, developmentFallback = false)
		}
		return if (environment.allowsDevelopmentAuthBypass()) fallback() else null
	}

	fun require(): ResolvedWorkspaceContext = current() ?: throw ApiException(
		HttpStatus.UNAUTHORIZED,
		"UNAUTHORIZED",
		"Authentication is required",
	)

	private fun fallback(): ResolvedWorkspaceContext {
		val actor = RequestActor(
			userId = DEV_USER_ID,
			workOSUserId = "plot-dev",
			email = "dev@plot.local",
			displayName = "Dev User",
		)
		val membership = WorkspaceMember(
			id = DEV_WORKSPACE_MEMBER_ID,
			workspaceId = DEV_WORKSPACE_ID,
			userId = DEV_USER_ID,
			role = "OWNER",
			status = "ACTIVE",
			joinedAt = Instant.EPOCH,
			createdAt = Instant.EPOCH,
			updatedAt = Instant.EPOCH,
		)
		return ResolvedWorkspaceContext(
			actor = actor,
			workspace = WorkspaceActor(actor, DEV_WORKSPACE_ID, "OWNER", membership),
			developmentFallback = true,
		)
	}

	companion object {
		val DEV_USER_ID: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000001")
		val DEV_WORKSPACE_ID: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000002")
		val DEV_WORKSPACE_MEMBER_ID: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000003")
	}
}
