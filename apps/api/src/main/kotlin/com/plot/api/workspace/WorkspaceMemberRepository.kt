package com.plot.api.workspace

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WorkspaceMemberRepository : JpaRepository<WorkspaceMember, UUID> {
	fun findByWorkspaceIdAndUserId(workspaceId: UUID, userId: UUID): WorkspaceMember?
	fun findByWorkspaceIdAndUserIdAndStatus(workspaceId: UUID, userId: UUID, status: String): WorkspaceMember?
	fun findAllByUserIdAndStatusOrderByCreatedAtAsc(userId: UUID, status: String): List<WorkspaceMember>
}
