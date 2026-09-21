package com.plot.api.billing

import java.util.UUID
import org.springframework.stereotype.Component

@Component
class PolarSubscriptionCheckoutService(
	private val properties: PolarProperties,
	private val checkoutProvider: PolarCheckoutProvider,
	private val creditProvider: PolarCreditProvider,
	private val contexts: WorkspaceBillingContextStore,
) {
	fun isConfigured(): Boolean = properties.creditsEnabled
		&& !properties.subscriptionProductId.isNullOrBlank()
		&& !properties.checkoutSuccessUrl.isNullOrBlank()

	fun create(workspaceId: UUID): WorkspaceCheckout {
		val context = contexts.requireContext(workspaceId)
		if (!context.trial && context.entitlementStatus != "revoked") {
			throw SubscriptionCheckoutNotAllowedException()
		}
		val productId = properties.subscriptionProductId?.trim()?.takeIf(String::isNotBlank)
			?: throw PolarApiException("POLAR_SUBSCRIPTION_NOT_CONFIGURED", "Polar subscription checkout is not configured")
		val successUrl = properties.checkoutSuccessUrl?.trim()?.takeIf(String::isNotBlank)
			?: throw PolarApiException("POLAR_SUBSCRIPTION_NOT_CONFIGURED", "Polar subscription checkout is not configured")
		val customer = creditProvider.ensureCustomer(
			workspaceId,
			context.ownerEmail,
			context.workspaceName,
			context.polarCustomerId,
		)
		if (context.polarCustomerId != customer.id) contexts.savePolarCustomerId(workspaceId, customer.id)
		val session = checkoutProvider.createCheckoutSession(
			workspaceId = workspaceId,
			productId = productId,
			customerName = context.workspaceName,
			customerEmail = context.ownerEmail,
			successUrl = successUrl,
			returnUrl = properties.checkoutReturnUrl?.trim()?.takeIf(String::isNotBlank),
			purpose = "subscription",
		)
		return WorkspaceCheckout(session.id, session.url)
	}
}

class SubscriptionCheckoutNotAllowedException : RuntimeException()
