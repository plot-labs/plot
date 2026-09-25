package com.plot.api.auth.workos

import com.workos.common.exceptions.NotFoundException
import com.workos.common.exceptions.WorkOSException
import org.springframework.stereotype.Service

data class WorkOSUserProfile(
	val id: String,
	val email: String,
	val emailVerified: Boolean,
	val displayName: String,
)

interface WorkOSUserGateway {
	fun get(userId: String): WorkOSUserProfile
}

interface WorkOSUserDeletionGateway {
	fun delete(userId: String)
}

@Service
class WorkOSUserService(
	private val clientProvider: WorkOSClientProvider,
) : WorkOSUserGateway, WorkOSUserDeletionGateway {
	override fun delete(userId: String) {
		try {
			clientProvider.require().userManagement.delete(userId)
		} catch (_: NotFoundException) {
			// A retry after a local transaction failure must still finish.
		} catch (exception: WorkOSException) {
			throw WorkOSProviderException("WorkOS user deletion failed", exception)
		}
	}

	override fun get(userId: String): WorkOSUserProfile {
		try {
			val user = clientProvider.require().userManagement.get(userId)
			val email = user.email.trim().lowercase().takeIf { it.isNotBlank() }
				?: throw WorkOSProviderException("WorkOS returned a user without an email", IllegalStateException("email is blank"))
			val displayName = user.name?.trim()?.takeIf { it.isNotBlank() }
				?: listOfNotNull(user.firstName, user.lastName)
					.joinToString(" ")
					.trim()
					.takeIf { it.isNotBlank() }
				?: email.substringBefore('@')
			return WorkOSUserProfile(
				id = user.id,
				email = email,
				emailVerified = user.emailVerified,
				displayName = displayName,
			)
		} catch (exception: NotFoundException) {
			throw WorkOSUserNotFoundException(userId, exception)
		} catch (exception: WorkOSProviderException) {
			throw exception
		} catch (exception: WorkOSException) {
			throw WorkOSProviderException("WorkOS user lookup failed", exception)
		}
	}
}
