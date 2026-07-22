package com.plot.api.dev

import com.plot.api.auth.RequestActorResolver
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class DevContext(
	private val actorResolver: RequestActorResolver? = null,
) {
	private val fallbackUserId = UUID.fromString("018fd000-0000-7000-8000-000000000001")
	private val fallbackWorkspaceId = UUID.fromString("018fd000-0000-7000-8000-000000000002")
	private val fallbackWorkspaceMemberId = UUID.fromString("018fd000-0000-7000-8000-000000000003")

	/** Compatibility surface for certification fixtures; authenticated requests resolve real actors. */
	val devUserId: UUID get() = actorResolver?.current()?.userId ?: fallbackUserId
	val devWorkspaceId: UUID get() = actorResolver?.currentWorkspace()?.workspaceId ?: fallbackWorkspaceId
	val devWorkspaceMemberId: UUID get() = actorResolver?.currentWorkspace()?.membership?.id ?: fallbackWorkspaceMemberId
}
