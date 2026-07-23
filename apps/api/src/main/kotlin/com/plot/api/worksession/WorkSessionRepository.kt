package com.plot.api.worksession

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WorkSessionRepository : JpaRepository<WorkSession, UUID> {
	@Query(
		"""
		select session from WorkSession session
		where session.workspaceId = :workspaceId
		order by coalesce(session.lastActivityAt, session.createdAt) desc, session.createdAt desc
		""",
	)
	fun findRecentByWorkspaceId(@Param("workspaceId") workspaceId: UUID): List<WorkSession>

	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WorkSession?
}
