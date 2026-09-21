package com.plot.api.billing

import java.util.UUID
import org.springframework.stereotype.Component

@Component
class PolarSubscriptionPortalService(
	private val properties: PolarProperties,
	private val portalProvider: PolarCustomerPortalProvider,
	private val creditProvider: PolarCreditProvider,
	private val contexts: WorkspaceBillingContextStore,
) {
	fun isConfigured(): Boolean = properties.creditsEnabled && returnUrl() != null

	fun create(workspaceId: UUID): PolarCustomerPortal {
		val context = contexts.requireContext(workspaceId)
		if (context.trial || context.entitlementStatus != "active") throw SubscriptionPortalNotAllowedException()
		val returnUrl = returnUrl()
			?: throw PolarApiException("POLAR_SUBSCRIPTION_NOT_CONFIGURED", "Polar customer portal is not configured")
		val customer = creditProvider.ensureCustomer(
			workspaceId,
			context.ownerEmail,
			context.workspaceName,
			context.polarCustomerId,
		)
		if (context.polarCustomerId != customer.id) contexts.savePolarCustomerId(workspaceId, customer.id)
		return portalProvider.createCustomerPortalSession(workspaceId, returnUrl)
	}

	private fun returnUrl(): String? = properties.customerPortalReturnUrl
		?.trim()
		?.takeIf(String::isNotBlank)
		?: properties.checkoutReturnUrl?.trim()?.takeIf(String::isNotBlank)
}

class SubscriptionPortalNotAllowedException : RuntimeException()
