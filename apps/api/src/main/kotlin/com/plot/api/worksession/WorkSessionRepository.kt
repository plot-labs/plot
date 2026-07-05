package com.plot.api.worksession

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WorkSessionRepository : JpaRepository<WorkSession, UUID> {
	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<WorkSession>
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WorkSession?
}
