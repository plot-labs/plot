package com.plot.api.writingblock

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface WritingBlockRepository : JpaRepository<WritingBlock, UUID> {
	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<WritingBlock>
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WritingBlock?
}
