package com.plot.api.entitlement

import com.plot.api.common.ApiException
import com.plot.api.auth.AuthorizedWorkspaceContext
import com.plot.api.workspace.WorkspaceRepository
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkspaceAccessService(
	private val authorizedWorkspaceContext: AuthorizedWorkspaceContext,
	private val workspaceRepository: WorkspaceRepository,
	private val entitlementReader: WorkspaceEntitlementReader,
) {
	@Transactional(noRollbackFor = [ApiException::class])
	fun requireWritable() = requireWritable(authorizedWorkspaceContext.require().workspace.workspaceId)

	@Transactional(noRollbackFor = [ApiException::class])
	fun requireWritable(workspaceId: UUID) {
		val entitlement = requireWorkspaceEntitlement(workspaceId)
		if (entitlement.accessMode != "full") {
			throw ApiException(
				HttpStatus.FORBIDDEN,
				"WORKSPACE_READ_ONLY",
				"This workspace does not allow changes.",
			)
		}
	}

	@Transactional(noRollbackFor = [ApiException::class])
	fun requireActiveWorkspace() = requireActiveWorkspace(authorizedWorkspaceContext.require().workspace.workspaceId)

	@Transactional(noRollbackFor = [ApiException::class])
	fun requireActiveWorkspace(workspaceId: UUID) {
		workspaceRepository.findByIdAndStatus(workspaceId, "ACTIVE")
			?: throw ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied")
	}

	@Transactional(noRollbackFor = [ApiException::class])
	fun requireCompletionAllowed() = requireCompletionAllowed(authorizedWorkspaceContext.require().workspace.workspaceId)

	@Transactional(noRollbackFor = [ApiException::class])
	fun requireCompletionAllowed(workspaceId: UUID) {
		val entitlement = requireWorkspaceEntitlement(workspaceId)
		if (entitlement.accessMode != "full" && entitlement.accessMode != "complete_only") {
			throw ApiException(
				HttpStatus.FORBIDDEN,
				"WORKSPACE_READ_ONLY",
				"This workspace does not allow new work.",
			)
		}
	}

	private fun requireWorkspaceEntitlement(workspaceId: UUID): EffectiveWorkspaceEntitlement {
		val workspace = workspaceRepository.findByIdAndStatus(workspaceId, "ACTIVE")
			?: throw ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied")
		return entitlementReader.resolve(workspace)
	}
}
