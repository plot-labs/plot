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
	val devUserId: UUID get() = authorizedWorkspaceContext?.require()?.actor?.userId ?: FALLBACK_USER_ID
	val devWorkspaceId: UUID get() = authorizedWorkspaceContext?.require()?.workspace?.workspaceId ?: FALLBACK_WORKSPACE_ID
	val devWorkspaceMemberId: UUID get() = authorizedWorkspaceContext?.require()?.workspace?.membership?.id ?: FALLBACK_WORKSPACE_MEMBER_ID

	private companion object {
		val FALLBACK_USER_ID = UUID.fromString("018fd000-0000-7000-8000-000000000001")
		val FALLBACK_WORKSPACE_ID = UUID.fromString("018fd000-0000-7000-8000-000000000002")
		val FALLBACK_WORKSPACE_MEMBER_ID = UUID.fromString("018fd000-0000-7000-8000-000000000003")
	}
}
