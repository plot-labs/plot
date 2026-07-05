package com.plot.api.workspace

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WorkspaceMemberRepository : JpaRepository<WorkspaceMember, UUID> {
	fun findByWorkspaceIdAndUserId(workspaceId: UUID, userId: UUID): WorkspaceMember?
}
