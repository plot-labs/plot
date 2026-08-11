package com.plot.api.entitlement

import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.workspace.WorkspaceRepository
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkspaceAccessService(
	private val devContext: DevContext,
	private val workspaceRepository: WorkspaceRepository,
	private val entitlementReader: WorkspaceEntitlementReader,
) {
	@Transactional(noRollbackFor = [ApiException::class])
	fun requireWritable() = requireWritable(devContext.devWorkspaceId)

	@Transactional(noRollbackFor = [ApiException::class])
	fun requireWritable(workspaceId: UUID) {
		val workspace = workspaceRepository.findByIdAndStatus(workspaceId, "ACTIVE")
			?: throw ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied")
		val entitlement = entitlementReader.resolve(workspace)
		if (
			workspace.entitlementStatus != entitlement.status ||
			workspace.accessMode != entitlement.accessMode
		) {
			val now = java.time.Instant.now()
			workspace.entitlementStatus = entitlement.status
			workspace.accessMode = entitlement.accessMode
			workspace.planUpdatedAt = now
			workspace.updatedAt = now
			workspaceRepository.save(workspace)
		}
		if (entitlement.accessMode != "full") {
			throw ApiException(
				HttpStatus.FORBIDDEN,
				"WORKSPACE_READ_ONLY",
				"This workspace is read-only. Reactivate a subscription to make changes.",
			)
		}
	}
}
