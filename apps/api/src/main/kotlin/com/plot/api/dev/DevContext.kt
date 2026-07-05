package com.plot.api.dev

import java.util.UUID
import org.springframework.stereotype.Component

@Component
class DevContext {
	val devUserId: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000001")
	val devWorkspaceId: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000002")
	val devWorkspaceMemberId: UUID = UUID.fromString("018fd000-0000-7000-8000-000000000003")
}
