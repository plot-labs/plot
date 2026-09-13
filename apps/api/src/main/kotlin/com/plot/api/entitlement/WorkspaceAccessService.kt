package com.plot.api.entitlement

import com.plot.api.common.ApiException
import com.plot.api.auth.AuthorizedWorkspaceContext
import com.plot.api.workspace.Workspace
import com.plot.api.workspace.WorkspaceRepository
import java.time.Instant
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
		val entitlement = persistDurable(workspaceId)
		if (entitlement.accessMode != "full") {
			throw ApiException(
				HttpStatus.FORBIDDEN,
				"WORKSPACE_READ_ONLY",
				"This workspace is read-only. Reactivate a subscription to make changes.",
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
		val entitlement = persistDurable(workspaceId)
		if (entitlement.accessMode != "full" && entitlement.accessMode != "complete_only") {
			throw ApiException(
				HttpStatus.FORBIDDEN,
				"WORKSPACE_READ_ONLY",
				"This workspace is read-only. Reactivate a subscription to make changes.",
			)
		}
	}

	private fun persistDurable(workspaceId: UUID): EffectiveWorkspaceEntitlement {
		val workspace = workspaceRepository.findByIdAndStatus(workspaceId, "ACTIVE")
			?: throw ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied")
		val entitlement = entitlementReader.resolve(workspace)
		persistDurable(workspace, entitlement)
		return entitlement
	}

	private fun persistDurable(workspace: Workspace, entitlement: EffectiveWorkspaceEntitlement) {
		val now = Instant.now()
		when (entitlement.accessMode) {
			"read_only" -> {
				if (workspace.entitlementStatus == entitlement.status && workspace.accessMode == "read_only") return
				workspace.entitlementStatus = entitlement.status
				workspace.accessMode = "read_only"
			}
			"complete_only", "full" -> {
				if (workspace.plan != "trial" || workspace.entitlementStatus == "revoked") return
				if (workspace.entitlementStatus == "trialing" && workspace.accessMode == "full") return
				workspace.entitlementStatus = "trialing"
				workspace.accessMode = "full"
			}
			else -> return
		}
		workspace.planUpdatedAt = now
		workspace.updatedAt = now
		workspaceRepository.save(workspace)
	}
}
