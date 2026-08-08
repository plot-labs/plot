package com.plot.api.workspace

import com.plot.api.common.ApiException
import com.plot.api.auth.RequestActorResolver
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceEntitlementReader
import com.plot.api.workspace.dto.WorkspaceResponse
import com.plot.api.workspace.dto.UpdateWorkspaceRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkspaceService(
	private val devContext: DevContext,
	private val workspaceRepository: WorkspaceRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val entitlementReader: WorkspaceEntitlementReader,
	private val actorResolver: RequestActorResolver? = null,
) {

	@Transactional(readOnly = true)
	fun get(id: UUID): WorkspaceResponse {
		val workspace = findDevWorkspace(id)
		val membership = memberRepository.findByWorkspaceIdAndUserIdAndStatus(id, devContext.devUserId, "ACTIVE")
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		return workspace.toResponse(entitlementReader.resolve(workspace), membership.role)
	}

	@Transactional
	fun update(id: UUID, request: UpdateWorkspaceRequest): WorkspaceResponse {
		val selectedWorkspace = actorResolver?.currentWorkspace()
		if (selectedWorkspace != null) {
			if (selectedWorkspace.workspaceId != id) throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
			if (selectedWorkspace.role != "OWNER") throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only workspace owners can update workspace settings")
		}

		val workspace = findDevWorkspace(id)
		val membership = memberRepository.findByWorkspaceIdAndUserIdAndStatus(id, devContext.devUserId, "ACTIVE")
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		if (selectedWorkspace == null && membership.role != "OWNER") {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only workspace owners can update workspace settings")
		}

		request.name?.let { value ->
			val name = value.trim()
			if (name.isBlank()) throw ApiException(HttpStatus.BAD_REQUEST, "WORKSPACE_NAME_REQUIRED", "Workspace name is required")
			workspace.name = name
		}
		request.logoUrl?.let { value ->
			val logoUrl = value.trim().ifBlank { null }
			if (logoUrl != null && !isAllowedLogoUrl(logoUrl)) {
				throw ApiException(HttpStatus.BAD_REQUEST, "WORKSPACE_LOGO_INVALID", "Workspace logo must be an image URL")
			}
			workspace.logoUrl = logoUrl
		}
		workspace.updatedAt = java.time.Instant.now()

		return workspace.toResponse(entitlementReader.resolve(workspace), selectedWorkspace?.role ?: membership.role)
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

	private fun isAllowedLogoUrl(value: String): Boolean = value.startsWith("https://")
		|| value.startsWith("http://")
		|| RASTER_LOGO_DATA_URL.matches(value)

	companion object {
		private val RASTER_LOGO_DATA_URL = Regex("^data:image/(?:png|jpeg|jpg|gif|webp);base64,[A-Za-z0-9+/=]+$")
	}
}
