package com.plot.api.dev

import com.plot.api.auth.AuthorizedWorkspaceContext
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class DevContext(
	private val authorizedWorkspaceContext: AuthorizedWorkspaceContext? = null,
) {
	/**
	 * Compatibility accessors for services not yet migrated to the explicit
	 * context type. Production callers still pass through its fail-closed gate.
	 */
	val devUserId: UUID get() = authorizedWorkspaceContext?.require()?.actor?.userId ?: AuthorizedWorkspaceContext.DEV_USER_ID
	val devWorkspaceId: UUID get() = authorizedWorkspaceContext?.require()?.workspace?.workspaceId ?: AuthorizedWorkspaceContext.DEV_WORKSPACE_ID
	val devWorkspaceMemberId: UUID get() = authorizedWorkspaceContext?.require()?.workspace?.membership?.id ?: AuthorizedWorkspaceContext.DEV_WORKSPACE_MEMBER_ID
}
