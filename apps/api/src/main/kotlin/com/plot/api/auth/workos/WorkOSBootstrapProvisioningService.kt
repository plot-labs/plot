package com.plot.api.auth.workos

import com.plot.api.auth.BootstrapAccountResponse
import com.plot.api.auth.WorkOSAuthProperties
import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.persistence.TransactionExecutor
import com.plot.api.workspace.User
import com.plot.api.workspace.UserRepository
import com.plot.api.workspace.Workspace
import com.plot.api.workspace.WorkspaceMember
import com.plot.api.workspace.WorkspaceMemberRepository
import com.plot.api.workspace.WorkspaceRepository
import java.time.Instant
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service

/**
 * Projects an already-authenticated WorkOS User into Plot's product identity.
 * Provider calls deliberately happen outside the local transaction; the ledger
 * records enough state to retry the same provider operations after a timeout or
 * a local commit failure.
 */
@Service
class WorkOSBootstrapProvisioningService(
	private val userRepository: UserRepository,
	private val workspaceRepository: WorkspaceRepository,
	private val memberRepository: WorkspaceMemberRepository,
	private val identityRepository: WorkOSIdentityMappingRepository,
	private val organizationRepository: WorkOSOrganizationMappingRepository,
	private val provisioningRepository: WorkOSProvisioningRepository,
	private val workOSUserGateway: WorkOSUserGateway,
	private val workOSOrganizationGateway: WorkOSOrganizationGateway,
	private val uuidGenerator: UuidGenerator,
	private val properties: WorkOSAuthProperties,
	private val transactionExecutor: TransactionExecutor,
) {
	fun bootstrap(jwt: Jwt): BootstrapAccountResponse {
		if (!properties.enabled) {
			throw unavailable("WORKOS_NOT_CONFIGURED", "WorkOS account provisioning is not configured")
		}
		if (!properties.bootstrapEnabled) {
			throw unavailable(
				"WORKOS_BOOTSTRAP_DISABLED",
				"WorkOS account provisioning is temporarily unavailable",
			)
		}

		val workOSUserId = jwt.subject?.trim()?.takeIf { it.isNotBlank() } ?: throw unauthorized()
		val providerUser = try {
			workOSUserGateway.get(workOSUserId)
		} catch (_: WorkOSUserNotFoundException) {
			throw unauthorized()
		} catch (failure: WorkOSNotConfiguredException) {
			throw unavailable("WORKOS_NOT_CONFIGURED", "WorkOS account provisioning is not configured", failure)
		} catch (failure: WorkOSProviderException) {
			throw unavailable(
				"WORKOS_PROVIDER_UNAVAILABLE",
				"WorkOS account provisioning is temporarily unavailable",
				failure,
			)
		}
		if (providerUser.id != workOSUserId) throw unauthorized()
		if (!providerUser.emailVerified) {
			throw ApiException(
				HttpStatus.FORBIDDEN,
				"EMAIL_VERIFICATION_REQUIRED",
				"Verify your email before continuing",
			)
		}

		identityRepository.findByWorkOSUserId(workOSUserId)?.let { mapping ->
			return existingResponse(mapping, workOSUserId)
		}
		if (userRepository.findByEmailIgnoreCase(providerUser.email) != null) {
			throw ApiException(
				HttpStatus.CONFLICT,
				"ACCOUNT_LINK_REQUIRED",
				"This email is linked to an existing Plot account",
			)
		}

		val idempotencyKey = "plot-bootstrap-$workOSUserId"
		val ledger = try {
			provisioningRepository.startOrResume(workOSUserId, idempotencyKey, Instant.now())
		} catch (failure: RuntimeException) {
			logger.error("WorkOS provisioning ledger could not be started for user {}", workOSUserId, failure)
			throw unavailable("BOOTSTRAP_RETRY_REQUIRED", "Account setup could not be started", failure)
		}

		return try {
			val organization = resolveOrganization(providerUser.displayName, workOSUserId, idempotencyKey, ledger)
			provisioningRepository.markOrganizationReady(workOSUserId, organization.id, Instant.now())
			val membership = workOSOrganizationGateway.ensureOwnerMembership(
				organizationId = organization.id,
				userId = workOSUserId,
				idempotencyKey = idempotencyKey,
			)
			createLocalProjection(providerUser, organization, membership, workOSUserId)
		} catch (failure: DataIntegrityViolationException) {
			resolveConcurrentProjection(providerUser, workOSUserId, failure)
		} catch (failure: ApiException) {
			markFailedSafely(workOSUserId, failure)
			throw failure
		} catch (failure: WorkOSNotConfiguredException) {
			markFailedSafely(workOSUserId, failure)
			throw unavailable("WORKOS_NOT_CONFIGURED", "WorkOS account provisioning is not configured", failure)
		} catch (failure: WorkOSProviderException) {
			markFailedSafely(workOSUserId, failure)
			throw unavailable(
				"WORKOS_PROVIDER_UNAVAILABLE",
				"WorkOS account provisioning is temporarily unavailable",
				failure,
			)
		} catch (failure: RuntimeException) {
			markFailedSafely(workOSUserId, failure)
			throw unavailable("BOOTSTRAP_RETRY_REQUIRED", "Account setup could not be completed", failure)
		}
	}

	private fun resolveOrganization(
		displayName: String,
		workOSUserId: String,
		idempotencyKey: String,
		ledger: WorkOSProvisioningRecord,
	): WorkOSOrganizationRecord {
		val externalId = personalOrganizationExternalId(workOSUserId)
		ledger.workOSOrganizationId?.let { knownOrganizationId ->
			workOSOrganizationGateway.findByExternalId(externalId)?.let { found ->
				if (found.id == knownOrganizationId) return found
			}
		}
		return workOSOrganizationGateway.findByExternalId(externalId)
			?: workOSOrganizationGateway.createPersonalOrganization(
				name = "${displayName.take(80)}'s Personal Workspace",
				externalId = externalId,
				idempotencyKey = idempotencyKey,
			)
	}

	private fun createLocalProjection(
		providerUser: WorkOSUserProfile,
		organization: WorkOSOrganizationRecord,
		membership: WorkOSMembershipRecord,
		workOSUserId: String,
	): BootstrapAccountResponse = transactionExecutor.execute {
			identityRepository.findByWorkOSUserId(workOSUserId)?.let { existing ->
				return@execute existingResponse(existing, workOSUserId)
			}
			if (userRepository.findByEmailIgnoreCase(providerUser.email) != null) {
				throw ApiException(
					HttpStatus.CONFLICT,
					"ACCOUNT_LINK_REQUIRED",
					"This email is linked to an existing Plot account",
				)
			}

			val now = Instant.now()
			val userId = uuidGenerator.next()
			val workspaceId = uuidGenerator.next()
			val memberId = uuidGenerator.next()
			val user = userRepository.save(User(
				id = userId,
				email = providerUser.email,
				displayName = providerUser.displayName,
				status = "ACTIVE",
				createdAt = now,
				updatedAt = now,
			))
			workspaceRepository.save(Workspace(
				id = workspaceId,
				name = "Personal",
				slug = "personal-${userId.toString().take(8)}",
				createdByUserId = userId,
				status = "ACTIVE",
				createdAt = now,
				updatedAt = now,
				plan = "none",
				entitlementStatus = "subscription_required",
				accessMode = "full",
			))
			memberRepository.save(WorkspaceMember(
				id = memberId,
				workspaceId = workspaceId,
				userId = userId,
				role = "OWNER",
				status = "ACTIVE",
				joinedAt = now,
				createdAt = now,
				updatedAt = now,
				workOSMembershipId = membership.id,
			))
			identityRepository.save(
				WorkOSIdentityMapping(workOSUserId, userId, providerUser.email, providerUser.emailVerified),
				now,
			)
			organizationRepository.save(
				WorkOSOrganizationMapping(organization.id, workOSUserId, workspaceId),
				now,
			)
			provisioningRepository.markCompleted(workOSUserId, userId, workspaceId, organization.id, now)
			BootstrapAccountResponse(user.id, workspaceId, organization.id, true)
		}

	private fun existingResponse(mapping: WorkOSIdentityMapping, workOSUserId: String): BootstrapAccountResponse {
		val user = userRepository.findById(mapping.plotUserId).orElse(null)
			?.takeIf { it.status == "ACTIVE" }
			?: throw unauthorized()
		val organization = organizationRepository.findPersonalByWorkOSUserId(workOSUserId)
			?: throw unavailable("BOOTSTRAP_RETRY_REQUIRED", "Account setup is still in progress")
		val workspace = workspaceRepository.findByIdAndStatus(organization.workspaceId, "ACTIVE")
			?: throw unavailable("BOOTSTRAP_RETRY_REQUIRED", "Account setup is still in progress")
		if (memberRepository.findByWorkspaceIdAndUserIdAndStatus(workspace.id, user.id, "ACTIVE") == null) {
			throw unavailable("BOOTSTRAP_RETRY_REQUIRED", "Account setup is still in progress")
		}
		return BootstrapAccountResponse(user.id, workspace.id, organization.workOSOrganizationId, false)
	}

	private fun resolveConcurrentProjection(
		providerUser: WorkOSUserProfile,
		workOSUserId: String,
		failure: Throwable,
	): BootstrapAccountResponse {
		identityRepository.findByWorkOSUserId(workOSUserId)?.let { mapping ->
			return existingResponse(mapping, workOSUserId)
		}
		if (userRepository.findByEmailIgnoreCase(providerUser.email) != null) {
			throw ApiException(
				HttpStatus.CONFLICT,
				"ACCOUNT_LINK_REQUIRED",
				"This email is linked to an existing Plot account",
			)
		}
		markFailedSafely(workOSUserId, failure)
		throw unavailable("BOOTSTRAP_RETRY_REQUIRED", "Account setup could not be committed", failure)
	}

	private fun markFailedSafely(workOSUserId: String, failure: Throwable) {
		runCatching { provisioningRepository.markFailed(workOSUserId, failure, Instant.now()) }
	}

	private fun personalOrganizationExternalId(workOSUserId: String) = "plot-personal-$workOSUserId"

	private fun unauthorized(): ApiException = ApiException(
		HttpStatus.UNAUTHORIZED,
		"UNAUTHORIZED",
		"Authentication is required",
	)

	private fun unavailable(error: String, message: String, cause: Throwable? = null): ApiException = ApiException(
		HttpStatus.SERVICE_UNAVAILABLE,
		error,
		message,
	).also { exception -> cause?.let(exception::initCause) }

	private companion object {
		val logger = LoggerFactory.getLogger(WorkOSBootstrapProvisioningService::class.java)
	}
}
