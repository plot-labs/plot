package com.plot.api.auth

import com.plot.api.auth.workos.WorkOSBootstrapProvisioningService
import java.util.UUID
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service

data class BootstrapAccountResponse(
	val userId: UUID,
	val workspaceId: UUID,
	val organizationId: String,
	val created: Boolean,
)

/** Compatibility facade for the account endpoint while WorkOS owns bootstrap orchestration. */
@Service
class AccountBootstrapService(
	private val workOSBootstrapProvisioningService: WorkOSBootstrapProvisioningService,
) {
	fun bootstrap(jwt: Jwt): BootstrapAccountResponse = workOSBootstrapProvisioningService.bootstrap(jwt)
}
