package com.plot.api.source

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface ConnectionRepository : JpaRepository<Connection, UUID> {
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): Connection?
	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<Connection>
}
