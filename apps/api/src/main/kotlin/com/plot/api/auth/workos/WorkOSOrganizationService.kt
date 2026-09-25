package com.plot.api.auth.workos

import com.plot.api.auth.WorkOSAuthProperties
import com.workos.common.exceptions.NotFoundException
import com.workos.common.exceptions.WorkOSException
import com.workos.common.http.RequestOptions
import com.workos.organizationmembership.CreateUserRole
import com.workos.types.OrganizationMembershipStatus
import org.springframework.stereotype.Service

data class WorkOSOrganizationRecord(
	val id: String,
	val externalId: String,
)

data class WorkOSMembershipRecord(
	val id: String,
	val organizationId: String,
	val userId: String,
)

interface WorkOSOrganizationGateway {
	fun findByExternalId(externalId: String): WorkOSOrganizationRecord?

	fun createOrganization(
		name: String,
		externalId: String,
		idempotencyKey: String,
	): WorkOSOrganizationRecord = createPersonalOrganization(name, externalId, idempotencyKey)

	fun createPersonalOrganization(
		name: String,
		externalId: String,
		idempotencyKey: String,
	): WorkOSOrganizationRecord

	fun ensureOwnerMembership(
		organizationId: String,
		userId: String,
		idempotencyKey: String,
	): WorkOSMembershipRecord
}

interface WorkOSOrganizationDeletionGateway {
	fun hasOtherActiveMembers(organizationId: String, userId: String): Boolean
	fun delete(organizationId: String)
}

@Service
class WorkOSOrganizationService(
	private val clientProvider: WorkOSClientProvider,
	private val properties: WorkOSAuthProperties,
) : WorkOSOrganizationGateway, WorkOSOrganizationDeletionGateway {
	override fun hasOtherActiveMembers(organizationId: String, userId: String): Boolean {
		try {
			return clientProvider.require().organizationMembership
				.list(organizationId = organizationId, statuses = listOf(OrganizationMembershipStatus.Active), limit = 100)
				.autoPagingIterable()
				.any { it.userId != userId }
		} catch (_: NotFoundException) {
			return false
		} catch (exception: WorkOSException) {
			throw WorkOSProviderException("WorkOS membership check failed", exception)
		}
	}

	override fun delete(organizationId: String) {
		try {
			clientProvider.require().organizations.delete(organizationId)
		} catch (_: NotFoundException) {
			// Retrying after a successful provider call is safe.
		} catch (exception: WorkOSException) {
			throw WorkOSProviderException("WorkOS organization deletion failed", exception)
		}
	}

	override fun findByExternalId(externalId: String): WorkOSOrganizationRecord? {
		try {
			return clientProvider.require().organizations.getByExternalId(externalId).toRecord(externalId)
		} catch (_: NotFoundException) {
			return null
		} catch (exception: WorkOSException) {
			throw WorkOSProviderException("WorkOS organization lookup failed", exception)
		}
	}

	override fun createPersonalOrganization(
		name: String,
		externalId: String,
		idempotencyKey: String,
	): WorkOSOrganizationRecord = createOrganization(name, externalId, idempotencyKey)

	override fun createOrganization(
		name: String,
		externalId: String,
		idempotencyKey: String,
	): WorkOSOrganizationRecord {
		try {
			val organization = clientProvider.require().organizations.create(
				name = name,
				allowProfilesOutsideOrganization = false,
				// WorkOS no longer accepts the deprecated `domains` field, even as
				// an empty array. Omit both optional domain fields until Plot has
				// a verified-domain provisioning flow.
				domains = null,
				domainData = null,
				metadata = emptyMap(),
				externalId = externalId,
				requestOptions = requestOptions(idempotencyKey),
			)
			return organization.toRecord(externalId)
		} catch (exception: WorkOSException) {
			throw WorkOSProviderException("WorkOS organization creation failed", exception)
		}
	}

	override fun ensureOwnerMembership(
		organizationId: String,
		userId: String,
		idempotencyKey: String,
	): WorkOSMembershipRecord {
		val workOS = clientProvider.require()
		val role = CreateUserRole.Single(properties.ownerRoleSlug)
		val options = requestOptions("$idempotencyKey:membership")
		try {
			return workOS.organizationMembership
				.create(role, organizationId, userId, options)
				.toRecord()
		} catch (failure: WorkOSException) {
			try {
				val existing = workOS.organizationMembership
					.list(organizationId = organizationId, userId = userId)
					.data
					.firstOrNull()
					?: throw failure
				if (existing.status == OrganizationMembershipStatus.Active) {
					return existing.toRecord()
				}
				return workOS.organizationMembership
					.update(existing.id, role, options)
					.toRecord()
			} catch (reconciliationFailure: WorkOSException) {
				throw WorkOSProviderException("WorkOS owner membership provisioning failed", reconciliationFailure)
			}
		}
	}

	private fun requestOptions(idempotencyKey: String): RequestOptions = RequestOptions.builder()
		.idempotencyKey(idempotencyKey)
		.build()
}

private fun com.workos.models.Organization.toRecord(fallbackExternalId: String) = WorkOSOrganizationRecord(
	id = id,
	externalId = externalId?.takeIf { it.isNotBlank() } ?: fallbackExternalId,
)

private fun com.workos.models.OrganizationMembership.toRecord() = WorkOSMembershipRecord(
	id = id,
	organizationId = organizationId,
	userId = userId,
)
