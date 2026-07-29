package com.plot.api.workspace

import com.plot.api.common.ApiException
import com.plot.api.auth.RequestActorResolver
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceEntitlementReader
import com.plot.api.workspace.dto.WorkspaceResponse
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
