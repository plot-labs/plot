package com.plot.api.source

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface ConnectionNamespaceBindingRepository : JpaRepository<ConnectionNamespaceBinding, UUID> {
	fun findByWorkspaceIdAndConnectionIdAndSourceNamespaceId(
		workspaceId: UUID,
		connectionId: UUID,
		sourceNamespaceId: UUID,
	): ConnectionNamespaceBinding?
}
