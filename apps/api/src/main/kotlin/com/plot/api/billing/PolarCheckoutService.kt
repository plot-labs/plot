package com.plot.api.billing

import java.util.UUID
import org.springframework.stereotype.Service

data class WorkspaceCheckout(val id: String, val url: String)

@Service
class PolarCheckoutService(
	private val properties: PolarProperties,
	private val checkoutProvider: PolarCheckoutProvider,
	private val creditProvider: PolarCreditProvider,
	private val contexts: WorkspaceBillingContextStore,
) {
	fun isConfigured(): Boolean = properties.creditsEnabled
		&& !properties.creditProductId.isNullOrBlank()
		&& !properties.checkoutSuccessUrl.isNullOrBlank()

	fun create(workspaceId: UUID): WorkspaceCheckout {
		val productId = properties.creditProductId?.trim()?.takeIf(String::isNotBlank)
			?: throw PolarApiException("POLAR_CHECKOUT_NOT_CONFIGURED", "Polar credit checkout is not configured")
		val successUrl = properties.checkoutSuccessUrl?.trim()?.takeIf(String::isNotBlank)
			?: throw PolarApiException("POLAR_CHECKOUT_NOT_CONFIGURED", "Polar credit checkout is not configured")

		val context = contexts.requireContext(workspaceId)
		val customer = creditProvider.ensureCustomer(
			workspaceId = workspaceId,
			ownerEmail = context.ownerEmail,
			workspaceName = context.workspaceName,
			existingCustomerId = context.polarCustomerId,
		)
		if (context.polarCustomerId != customer.id) contexts.savePolarCustomerId(workspaceId, customer.id)

		val session = checkoutProvider.createCheckoutSession(
			workspaceId = workspaceId,
			productId = productId,
			customerName = context.workspaceName,
			customerEmail = context.ownerEmail,
			successUrl = successUrl,
			returnUrl = properties.checkoutReturnUrl?.trim()?.takeIf(String::isNotBlank),
		)
		return WorkspaceCheckout(session.id, session.url)
	}
}
