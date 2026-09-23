package com.plot.api.workspace

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.auth.RequestActorResolver
import com.plot.api.auth.WorkOSAuthProperties
import com.plot.api.auth.workos.WorkOSOrganizationMappingRepository
import com.plot.api.auth.workos.WorkOSOrganizationGateway
import com.plot.api.auth.workos.WorkOSProviderException
import com.plot.api.auth.workos.WorkOSWorkspaceProvisioningRepository
import com.plot.api.persistence.TransactionExecutor
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceEntitlementReader
import com.plot.api.entitlement.WorkspacePolicy
import com.plot.api.workspace.dto.CreateWorkspaceRequest
import com.plot.api.workspace.dto.WorkspaceResponse
import com.plot.api.workspace.dto.UpdateWorkspaceRequest
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
	private val entitlementReader: WorkspaceEntitlementReader,
	private val uuidGenerator: UuidGenerator,
	private val actorResolver: RequestActorResolver? = null,
	private val workOSProperties: WorkOSAuthProperties,
	private val workOSOrganizationMappingRepository: WorkOSOrganizationMappingRepository,
	private val workOSOrganizationGateway: WorkOSOrganizationGateway,
	private val workOSWorkspaceProvisioningRepository: WorkOSWorkspaceProvisioningRepository,
	private val transactionExecutor: TransactionExecutor,
) {

	fun create(request: CreateWorkspaceRequest, idempotencyKey: String? = null): WorkspaceResponse {
		val actor = actorResolver?.current()
		val userId = actor?.userId ?: devContext.devUserId
		if (workOSProperties.enabled && actor != null) return createWorkOSWorkspace(request, actor.userId, actor.workOSUserId, idempotencyKey)
		return transactionExecutor.execute { createLocalWorkspace(request, userId) }
	}

	private fun createLocalWorkspace(request: CreateWorkspaceRequest, userId: UUID): WorkspaceResponse {
		val activeWorkspaces = memberRepository.countByUserIdAndStatus(userId, "ACTIVE")
		if (activeWorkspaces >= WorkspacePolicy.MAX_ACTIVE_PER_USER) {
			throw ApiException(HttpStatus.FORBIDDEN, "WORKSPACE_LIMIT_REACHED", "Workspace limit reached")
		}
		val now = Instant.now()
		val workspaceId = uuidGenerator.next()
		val workspace = workspaceRepository.save(Workspace(
			id = workspaceId,
			name = request.name.trim(),
			// Leading UUIDv7 characters are timestamp bits and repeat for every
			// creation within the same window, so seed from the random tail.
			slug = "workspace-${workspaceId.toString().replace("-", "").takeLast(12)}",
			createdByUserId = userId,
			status = "ACTIVE",
			createdAt = now,
			updatedAt = now,
			plan = "none",
			entitlementStatus = "subscription_required",
			accessMode = "read_only",
		))
		memberRepository.save(WorkspaceMember(
			id = uuidGenerator.next(),
			workspaceId = workspace.id,
			userId = userId,
			role = "OWNER",
			status = "ACTIVE",
			joinedAt = now,
			createdAt = now,
			updatedAt = now,
		))
		return toResponse(workspace, "OWNER")
	}

	private fun createWorkOSWorkspace(
		request: CreateWorkspaceRequest,
		userId: UUID,
		workOSUserId: String,
		requestIdempotencyKey: String?,
	): WorkspaceResponse {
		val name = request.name.trim()
		if (name.isBlank()) throw ApiException(HttpStatus.BAD_REQUEST, "WORKSPACE_NAME_REQUIRED", "Workspace name is required")
		val callerKey = requestIdempotencyKey?.trim().takeIf { !it.isNullOrBlank() }
		if (callerKey != null && !IDEMPOTENCY_KEY.matches(callerKey)) {
			throw ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency key is invalid")
		}
		val stableKey = "plot-workspace-$userId-${callerKey ?: UUID.randomUUID()}"
		val existing = workOSWorkspaceProvisioningRepository.findByIdempotencyKey(stableKey)
		val workspaceId = existing?.workspaceId ?: uuidGenerator.next()
		val externalId = workspaceExternalId(workspaceId)
		val ledger = workOSWorkspaceProvisioningRepository.startOrResume(
			workOSOrganizationId = externalId,
			workOSUserId = workOSUserId,
			workspaceId = workspaceId,
			idempotencyKey = stableKey,
			now = Instant.now(),
		)
		if (ledger.workOSUserId != workOSUserId) {
			throw ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was reused")
		}
		if (ledger.state == "COMPLETED") {
			val completedWorkspace = ledger.workspaceId?.let { workspaceRepository.findByIdAndStatus(it, "ACTIVE") }
			if (completedWorkspace != null) {
				val member = memberRepository.findByWorkspaceIdAndUserIdAndStatus(completedWorkspace.id, userId, "ACTIVE")
					?: throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKSPACE_PROVISIONING_RETRY_REQUIRED", "Workspace setup is still in progress")
				return toResponse(completedWorkspace, member.role)
			}
		}

		return try {
			val organization = workOSOrganizationGateway.findByExternalId(externalId)
				?: workOSOrganizationGateway.createOrganization(name.take(80), externalId, stableKey)
			workOSOrganizationMappingRepository.findByOrganizationId(organization.id)?.let { mapping ->
				if (mapping.workspaceId != workspaceId) throw ApiException(HttpStatus.CONFLICT, "WORKSPACE_PROVISIONING_CONFLICT", "Workspace setup could not be reconciled")
			}
			workOSWorkspaceProvisioningRepository.markOrganizationReady(externalId, Instant.now())
			val membership = workOSOrganizationGateway.ensureOwnerMembership(
				organizationId = organization.id,
				userId = workOSUserId,
				idempotencyKey = stableKey,
			)
			transactionExecutor.execute {
				val now = Instant.now()
				val workspace = workspaceRepository.findByIdAndStatus(workspaceId, "ACTIVE") ?: workspaceRepository.save(Workspace(
					id = workspaceId,
					name = name,
					slug = "workspace-${workspaceId.toString().replace("-", "").takeLast(12)}",
					createdByUserId = userId,
					status = "ACTIVE",
					createdAt = now,
					updatedAt = now,
					plan = "none",
					entitlementStatus = "subscription_required",
					accessMode = "read_only",
				))
				val member = memberRepository.upsertWorkOSProjection(
					workspaceId = workspace.id,
					userId = userId,
					role = "OWNER",
					status = "ACTIVE",
					workOSMembershipId = membership.id,
					now = now,
				)
				workOSOrganizationMappingRepository.save(
					com.plot.api.auth.workos.WorkOSOrganizationMapping(organization.id, workOSUserId, workspace.id),
					now,
				)
				// The ledger is keyed by the deterministic external ID used before
				// the provider resource exists. Keep using that key after the
				// provider returns its opaque Organization ID so retries can reach
				// the COMPLETED row and converge on the same local Workspace.
				workOSWorkspaceProvisioningRepository.markCompleted(externalId, workspace.id, now)
				toResponse(workspace, member.role)
			}
		} catch (failure: ApiException) {
			workOSWorkspaceProvisioningRepository.markFailed(externalId, failure, Instant.now())
			throw failure
		} catch (failure: WorkOSProviderException) {
			workOSWorkspaceProvisioningRepository.markFailed(externalId, failure, Instant.now())
			throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKOS_PROVIDER_UNAVAILABLE", "Workspace setup is temporarily unavailable")
		} catch (failure: RuntimeException) {
			workOSWorkspaceProvisioningRepository.markFailed(externalId, failure, Instant.now())
			throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKSPACE_PROVISIONING_RETRY_REQUIRED", "Workspace setup could not be completed")
		}
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): WorkspaceResponse {
		val workspace = findDevWorkspace(id)
		val membership = memberRepository.findByWorkspaceIdAndUserIdAndStatus(id, devContext.devUserId, "ACTIVE")
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace not found")
		return toResponse(workspace, membership.role)
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
		request.publicCitationsEnabled?.let { enabled ->
			workspace.publicCitationsEnabled = enabled
		}
		workspace.updatedAt = java.time.Instant.now()
		workspaceRepository.save(workspace)

		return toResponse(workspace, selectedWorkspace?.role ?: membership.role)
	}

	private fun toResponse(workspace: Workspace, role: String) = workspace
		.toResponse(entitlementReader.resolve(workspace), role)
		.copy(
			organizationId = if (workOSProperties.enabled) {
				workOSOrganizationMappingRepository.findByWorkspaceId(workspace.id)?.workOSOrganizationId
			} else null,
		)

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

	// Remote logos must be https so authenticated pages never issue plain-http
	// requests (mixed content) and self-contained raster data URLs stay valid.
	private fun isAllowedLogoUrl(value: String): Boolean = value.startsWith("https://")
		|| RASTER_LOGO_DATA_URL.matches(value)

	companion object {
		private val RASTER_LOGO_DATA_URL = Regex("^data:image/(?:png|jpeg|jpg|gif|webp);base64,[A-Za-z0-9+/=]+$")
		private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{8,200}$")
		private fun workspaceExternalId(workspaceId: UUID) = "plot-workspace-$workspaceId"
	}
}
