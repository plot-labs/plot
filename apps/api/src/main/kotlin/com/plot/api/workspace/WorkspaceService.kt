package com.plot.api.workspace

import com.plot.api.common.ApiException
import com.plot.api.auth.RequestActorResolver
import com.plot.api.dev.DevContext
import com.plot.api.workspace.dto.UpdateWorkspaceRequest
import com.plot.api.workspace.dto.WorkspaceResponse
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkspaceService(
	private val devContext: DevContext,
	private val workspaceRepository: WorkspaceRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val actorResolver: RequestActorResolver? = null,
) {

	@Transactional(readOnly = true)
	fun list(): List<WorkspaceResponse> {
		val memberships = memberRepository.findAllByUserIdAndStatusOrderByCreatedAtAsc(devContext.devUserId, "ACTIVE")
		val workspaces = workspaceRepository.findAllByIdInAndStatus(memberships.map { it.workspaceId }, "ACTIVE").associateBy { it.id }
		return memberships.mapNotNull { member -> workspaces[member.workspaceId]?.toResponse(member.role) }
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): WorkspaceResponse {
		val workspace = findDevWorkspace(id)
		val membership = memberRepository.findByWorkspaceIdAndUserIdAndStatus(id, devContext.devUserId, "ACTIVE")
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		return workspace.toResponse(membership.role)
	}

	@Transactional
	fun update(id: UUID, request: UpdateWorkspaceRequest): WorkspaceResponse {
		val trimmedName = request.name.trim()
		val trimmedSlug = request.slug.trim()
		val workspace = findDevWorkspace(id)
		val member = memberRepository.findByWorkspaceIdAndUserIdAndStatus(id, devContext.devUserId, "ACTIVE")
		if (member?.role != "OWNER") throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Workspace owner access is required")
		if (workspaceRepository.existsBySlugAndIdNot(trimmedSlug, workspace.id)) {
			throw ApiException(HttpStatus.CONFLICT, "CONFLICT", "Workspace slug already exists")
		}

		workspace.name = trimmedName
		workspace.slug = trimmedSlug
		workspace.updatedAt = Instant.now()
		return workspace.toResponse()
	}

	private fun findDevWorkspace(id: UUID): Workspace {
		// The path identifier may never override the BFF-selected tenant. Keep
		// cross-workspace existence private even when a user belongs to both.
		if (actorResolver?.current() != null && actorResolver.requireWorkspace().workspaceId != id) {
			throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		}
		val member = memberRepository.findByWorkspaceIdAndUserIdAndStatus(id, devContext.devUserId, "ACTIVE")
		if (member == null) throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		return workspaceRepository.findByIdAndStatus(id, "ACTIVE")
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
	}
}
