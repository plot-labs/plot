package com.plot.api.auth

import com.plot.api.auth.workos.WorkOSOrganizationMappingRepository
import com.plot.api.auth.workos.WorkOSMembershipGateway
import com.plot.api.auth.workos.WorkOSMembershipProjectionService
import com.plot.api.auth.workos.WorkOSProviderException
import com.plot.api.entitlement.WorkspaceCapabilities
import com.plot.api.entitlement.WorkspaceEntitlementReader
import com.plot.api.workspace.UserRepository
import com.plot.api.workspace.WorkspaceMemberRepository
import com.plot.api.workspace.WorkspaceRepository
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class CurrentAccountUser(val id: UUID, val email: String, val displayName: String)
data class CurrentAccountWorkspace(
	val id: UUID,
	val name: String,
	val slug: String,
	val logoUrl: String?,
	val organizationId: String?,
	val role: String,
	val plan: String,
	val entitlementStatus: String,
	val accessMode: String,
	val capabilities: WorkspaceCapabilities,
	val trialEndsAt: java.time.Instant,
)
data class CurrentAccountResponse(
	val user: CurrentAccountUser,
	val workspaces: List<CurrentAccountWorkspace>,
	val defaultWorkspaceId: UUID,
	val activeOrganizationId: String?,
)

@RestController
@RequestMapping("/api/me")
class CurrentAccountController(
	private val actorResolver: RequestActorResolver,
	private val userRepository: UserRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val workspaceRepository: WorkspaceRepository,
	private val entitlementReader: WorkspaceEntitlementReader,
	private val workOSProperties: WorkOSAuthProperties,
	private val workOSOrganizationMappingRepository: WorkOSOrganizationMappingRepository,
	private val workOSMembershipGateway: WorkOSMembershipGateway,
	private val workOSMembershipProjectionService: WorkOSMembershipProjectionService,
) {
	@GetMapping
	fun me(): ResponseEntity<CurrentAccountResponse> {
		val actor = actorResolver.requireActor()
		val user = userRepository.findById(actor.userId).orElseThrow { IllegalStateException("Authenticated user disappeared") }
		if (workOSProperties.enabled) {
			try {
				workOSMembershipGateway.listForUser(actor.workOSUserId).forEach(workOSMembershipProjectionService::reconcile)
			} catch (failure: WorkOSProviderException) {
				throw com.plot.api.common.ApiException(
					org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
					"WORKOS_MEMBERSHIP_REFRESH_UNAVAILABLE",
					"Workspace access could not be refreshed",
				).also { it.initCause(failure) }
			}
		}
		val memberships = memberRepository.findAllByUserIdAndStatusOrderByCreatedAtAsc(actor.userId, "ACTIVE")
		val workspaces = workspaceRepository.findAllByIdInAndStatus(memberships.map { it.workspaceId }, "ACTIVE")
			.associateBy { it.id }
		val response = memberships.mapNotNull { member ->
			workspaces[member.workspaceId]?.let { workspace ->
				val entitlement = entitlementReader.resolve(workspace)
				CurrentAccountWorkspace(
					workspace.id,
					workspace.name,
					workspace.slug,
					workspace.logoUrl,
					if (workOSProperties.enabled) {
						workOSOrganizationMappingRepository.findByWorkspaceId(workspace.id)?.workOSOrganizationId
					} else null,
					member.role,
					workspace.plan,
					entitlement.status,
					entitlement.accessMode,
					entitlement.capabilities,
					workspace.trialEndsAt,
				)
			}
		}
		val activeOrganizationId = actorResolver.currentWorkOSOrganizationId()
		val activeWorkspaceId = activeOrganizationId
			?.let { organizationId -> response.firstOrNull { it.organizationId == organizationId }?.id }
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
			CurrentAccountResponse(
				CurrentAccountUser(user.id, user.email, user.displayName),
				response,
				activeWorkspaceId ?: response.firstOrNull()?.id ?: throw com.plot.api.common.ApiException(
					org.springframework.http.HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied",
				),
				activeOrganizationId,
			),
		)
	}
}
