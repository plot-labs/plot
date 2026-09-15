package com.plot.api.auth.workos

import com.plot.api.auth.WorkOSAuthProperties
import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.JooqTransactionExecutor
import com.plot.api.workspace.WorkspaceMemberRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

data class WorkOSMembershipProjectionResult(
	val workspaceId: UUID,
	val userId: UUID,
	val role: String,
	val active: Boolean,
)

/** Applies provider membership state to Plot's join/projection row. */
@Service
class WorkOSMembershipProjectionService(
	private val identityRepository: WorkOSIdentityMappingRepository,
	private val organizationRepository: WorkOSOrganizationMappingRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val uuidGenerator: UuidGenerator,
	private val properties: WorkOSAuthProperties,
	private val transactionExecutor: JooqTransactionExecutor,
) {
	fun reconcile(snapshot: WorkOSMembershipSnapshot): WorkOSMembershipProjectionResult? {
		val identity = identityRepository.findByWorkOSUserId(snapshot.userId) ?: return null
		val organization = organizationRepository.findByOrganizationId(snapshot.organizationId) ?: return null
		return apply(
			workspaceId = organization.workspaceId,
			userId = identity.plotUserId,
			workOSMembershipId = snapshot.id,
			roleSlug = snapshot.roleSlug,
			status = snapshot.status,
		)
	}

	fun reconcileToken(
		workOSUserId: String,
		organizationId: String,
		roleSlug: String?,
	): WorkOSMembershipProjectionResult? {
		val identity = identityRepository.findByWorkOSUserId(workOSUserId) ?: return null
		val organization = organizationRepository.findByOrganizationId(organizationId) ?: return null
		return apply(
			workspaceId = organization.workspaceId,
			userId = identity.plotUserId,
			workOSMembershipId = null,
			roleSlug = roleSlug,
			status = "active",
		)
	}

	private fun apply(
		workspaceId: UUID,
		userId: UUID,
		workOSMembershipId: String?,
		roleSlug: String?,
		status: String,
	): WorkOSMembershipProjectionResult = transactionExecutor.executeRequiresNew {
			val role = normalizeWorkOSRole(roleSlug, properties.ownerRoleSlug)
			val active = status.equals("active", ignoreCase = true) && role != null
			val member = memberRepository.upsertWorkOSProjection(
				workspaceId = workspaceId,
				userId = userId,
				role = role ?: "MEMBER",
				status = if (active) "ACTIVE" else "INACTIVE",
				workOSMembershipId = workOSMembershipId,
				now = Instant.now(),
			)
			WorkOSMembershipProjectionResult(member.workspaceId, member.userId, member.role, member.status == "ACTIVE")
		}
}
