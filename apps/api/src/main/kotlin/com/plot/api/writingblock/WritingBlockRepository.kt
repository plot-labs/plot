package com.plot.api.writingblock

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface WritingBlockRepository : JpaRepository<WritingBlock, UUID> {
	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<WritingBlock>
	fun findAllByWorkspaceId(workspaceId: UUID, pageable: Pageable): Page<WritingBlock>
	@Query(
		"""
		select b from WritingBlock b
		where b.workspaceId = :workspaceId
		  and exists (
		    select 1 from WritingBlockScope membership
		    where membership.workspaceId = :workspaceId
		      and membership.sourceScopeId = :sourceScopeId
		      and membership.writingBlockId = b.id
		      and membership.status = 'ACTIVE'
		  )
		""",
	)
	fun findAllByWorkspaceIdAndSourceScopeId(
		workspaceId: UUID,
		sourceScopeId: UUID,
		pageable: Pageable,
	): Page<WritingBlock>
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WritingBlock?
}
