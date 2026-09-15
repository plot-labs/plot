package com.plot.api.auth.workos

import com.workos.common.exceptions.NotFoundException
import com.workos.common.exceptions.WorkOSException
import com.workos.types.OrganizationMembershipStatus
import org.springframework.stereotype.Service

data class WorkOSMembershipSnapshot(
	val id: String,
	val organizationId: String,
	val userId: String,
	val status: String,
	val roleSlug: String?,
)

interface WorkOSMembershipGateway {
	fun find(organizationId: String, userId: String): WorkOSMembershipSnapshot?

	fun listForUser(userId: String): List<WorkOSMembershipSnapshot>
}

/**
 * WorkOS membership reads live behind a small provider-neutral boundary. The
 * projection and authorization layers can therefore use deterministic fakes
 * in tests without teaching the rest of Plot about SDK response types.
 */
@Service
class WorkOSMembershipService(
	private val clientProvider: WorkOSClientProvider,
) : WorkOSMembershipGateway {
	private val statuses = listOf(
		OrganizationMembershipStatus.Active,
		OrganizationMembershipStatus.Inactive,
		OrganizationMembershipStatus.Pending,
	)

	override fun find(organizationId: String, userId: String): WorkOSMembershipSnapshot? {
		try {
			return clientProvider.require().organizationMembership
				.list(organizationId = organizationId, userId = userId, statuses = statuses)
				.data
				.firstOrNull { it.organizationId == organizationId && it.userId == userId }
				?.toSnapshot()
		} catch (_: NotFoundException) {
			return null
		} catch (exception: WorkOSException) {
			throw WorkOSProviderException("WorkOS membership lookup failed", exception)
		}
	}

	override fun listForUser(userId: String): List<WorkOSMembershipSnapshot> {
		try {
			return clientProvider.require().organizationMembership
				.list(userId = userId, statuses = statuses, limit = 100)
				.data
				.filter { it.userId == userId }
				.map { it.toSnapshot() }
		} catch (exception: WorkOSException) {
			throw WorkOSProviderException("WorkOS memberships could not be listed", exception)
		}
	}
}

internal fun normalizeWorkOSRole(roleSlug: String?, ownerRoleSlug: String): String? = when (roleSlug?.trim()?.lowercase()) {
	ownerRoleSlug.lowercase() -> "OWNER"
	"member" -> "MEMBER"
	else -> null
}

private fun com.workos.models.OrganizationMembership.toSnapshot() = WorkOSMembershipSnapshot(
	id = id,
	organizationId = organizationId,
	userId = userId,
	status = status.value.lowercase(),
	roleSlug = role.slug.takeIf { it.isNotBlank() } ?: roles.firstOrNull()?.slug,
)
