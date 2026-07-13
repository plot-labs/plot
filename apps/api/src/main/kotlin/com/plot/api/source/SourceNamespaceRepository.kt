package com.plot.api.source

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface SourceNamespaceRepository : JpaRepository<SourceNamespace, UUID> {
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): SourceNamespace?
}
