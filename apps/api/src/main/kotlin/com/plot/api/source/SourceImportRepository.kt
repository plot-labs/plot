package com.plot.api.source

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface SourceImportRepository : JpaRepository<SourceImport, UUID> {
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): SourceImport?
	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<SourceImport>
}
