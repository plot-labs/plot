package com.plot.api.workspace

import com.plot.api.common.ApiException
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
) {

	@Transactional(readOnly = true)
	fun list(): List<WorkspaceResponse> {
		return workspaceRepository
			.findAllByIdAndStatus(devContext.devWorkspaceId, "ACTIVE")
			.map { it.toResponse() }
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): WorkspaceResponse {
		return findDevWorkspace(id).toResponse()
	}

	@Transactional
	fun update(id: UUID, request: UpdateWorkspaceRequest): WorkspaceResponse {
		val trimmedName = request.name.trim()
		val trimmedSlug = request.slug.trim()
		val workspace = findDevWorkspace(id)
		if (workspaceRepository.existsBySlugAndIdNot(trimmedSlug, workspace.id)) {
			throw ApiException(HttpStatus.CONFLICT, "CONFLICT", "Workspace slug already exists")
		}

		workspace.name = trimmedName
		workspace.slug = trimmedSlug
		workspace.updatedAt = Instant.now()
		return workspace.toResponse()
	}

	private fun findDevWorkspace(id: UUID): Workspace {
		if (id != devContext.devWorkspaceId) {
			throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		}

		return workspaceRepository.findByIdAndStatus(id, "ACTIVE")
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
	}
}
