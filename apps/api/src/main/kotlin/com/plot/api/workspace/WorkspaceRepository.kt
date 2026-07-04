package com.plot.api.workspace

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WorkspaceRepository : JpaRepository<Workspace, UUID> {
	fun findByIdAndStatus(id: UUID, status: String): Workspace?
	fun findAllByIdAndStatus(id: UUID, status: String): List<Workspace>
}
