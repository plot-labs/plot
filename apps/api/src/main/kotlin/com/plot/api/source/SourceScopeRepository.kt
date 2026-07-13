package com.plot.api.source

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface SourceScopeRepository : JpaRepository<SourceScope, UUID> {
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): SourceScope?
	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<SourceScope>
}
