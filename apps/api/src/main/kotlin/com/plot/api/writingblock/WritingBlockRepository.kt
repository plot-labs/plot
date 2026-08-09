package com.plot.api.writingblock

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.query.Param

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

	@Query(
		"""
		select b.* from writing_blocks b
		where b.workspace_id = :workspaceId
		  and b.status = 'ACTIVE'
		  and (
		    cast(:cursorSequence as bigint) is null
		    or b.activity_sequence > cast(:cursorSequence as bigint)
		  )
		  and exists (
		    select 1 from source_scopes scope
		    where scope.workspace_id = :workspaceId
		      and scope.id = :sourceScopeId
		      and scope.status = 'ACTIVE'
		  )
		  and exists (
		    select 1 from writing_block_scopes membership
		    where membership.workspace_id = :workspaceId
		      and membership.source_scope_id = :sourceScopeId
		      and membership.writing_block_id = b.id
		      and membership.status = 'ACTIVE'
		  )
		order by b.activity_sequence asc
		""",
		nativeQuery = true,
	)
	fun findActiveAfterActivityCursor(
		@Param("workspaceId") workspaceId: UUID,
		@Param("sourceScopeId") sourceScopeId: UUID,
		@Param("cursorSequence") cursorSequence: Long?,
		pageable: Pageable,
	): List<WritingBlock>

	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WritingBlock?

	@Query(
		"""
		select b from WritingBlock b
		where b.workspaceId = :workspaceId
		  and b.id in :ids
		  and b.status = 'ACTIVE'
		  and exists (
		    select 1 from SourceScope scope
		    where scope.workspaceId = :workspaceId
		      and scope.id = :sourceScopeId
		      and scope.status = 'ACTIVE'
		  )
		  and exists (
		    select 1 from WritingBlockScope membership
		    where membership.workspaceId = :workspaceId
		      and membership.sourceScopeId = :sourceScopeId
		      and membership.writingBlockId = b.id
		      and membership.status = 'ACTIVE'
		  )
		""",
	)
	fun findSelectedReadable(
		@Param("workspaceId") workspaceId: UUID,
		@Param("sourceScopeId") sourceScopeId: UUID,
		@Param("ids") ids: Collection<UUID>,
	): List<WritingBlock>
}
