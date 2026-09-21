package com.plot.api.billing

import com.plot.api.auth.AuthorizedWorkspaceContext
import com.plot.api.common.ApiException
import com.plot.api.entitlement.ReadOnlyAllowed
import java.time.Instant
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class CreditUsageEventResponse(
	val id: String,
	val timestamp: Instant,
	val credits: Long,
	val provider: String?,
	val model: String?,
)

data class WorkspaceCreditOverviewResponse(
	val balance: Long,
	val creditedUnits: Long,
	val consumedUnits: Long,
	val usageEvents: List<CreditUsageEventResponse>,
	val checkoutAvailable: Boolean,
)

data class WorkspaceCheckoutResponse(
	val checkoutId: String,
	val url: String,
)

data class WorkspaceCustomerPortalResponse(
	val url: String,
)

@RestController
@RequestMapping("/api/billing")
class CreditController(
	private val credits: PolarCreditService,
	private val checkout: PolarCheckoutService,
	private val subscriptionCheckout: PolarSubscriptionCheckoutService,
	private val subscriptionPortal: PolarSubscriptionPortalService,
	private val authorizedWorkspaceContext: AuthorizedWorkspaceContext,
) {
	@GetMapping("/credits")
	fun get(): ResponseEntity<WorkspaceCreditOverviewResponse> {
		val workspaceId = authorizedWorkspaceContext.require().workspace.workspaceId
		val overview = try {
			credits.readOverview(workspaceId)
		} catch (failure: AiCreditControlException) {
			throw ApiException(
				if (failure.recoverable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY,
				failure.safeCode,
				"AI credit balance is temporarily unavailable",
			)
		}
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(overview.toResponse(checkout.isConfigured()))
	}

	@PostMapping("/checkout")
	fun createCheckout(): ResponseEntity<WorkspaceCheckoutResponse> {
		val context = authorizedWorkspaceContext.require()
		if (context.workspace.role != "OWNER") {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only workspace owners can purchase credits")
		}
		val session = try {
			checkout.create(context.workspace.workspaceId)
		} catch (failure: PolarApiException) {
			throw ApiException(
				if (failure.retryable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY,
				failure.safeCode,
				"Credit checkout could not be started",
			)
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(
			WorkspaceCheckoutResponse(session.id, session.url),
		)
	}

	@PostMapping("/subscription-checkout")
	@ReadOnlyAllowed
	fun createSubscriptionCheckout(): ResponseEntity<WorkspaceCheckoutResponse> {
		val context = authorizedWorkspaceContext.require()
		if (context.workspace.role != "OWNER") {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only workspace owners can start a subscription")
		}
		val session = try {
			subscriptionCheckout.create(context.workspace.workspaceId)
		} catch (_: SubscriptionCheckoutNotAllowedException) {
			throw ApiException(
				HttpStatus.CONFLICT,
				"SUBSCRIPTION_MANAGEMENT_REQUIRED",
				"Manage the current subscription instead of starting another checkout",
			)
		} catch (failure: PolarApiException) {
			throw ApiException(
				if (failure.retryable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY,
				failure.safeCode,
				"Subscription checkout could not be started",
			)
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(
			WorkspaceCheckoutResponse(session.id, session.url),
		)
	}

	@PostMapping("/subscription-portal")
	@ReadOnlyAllowed
	fun createSubscriptionPortal(): ResponseEntity<WorkspaceCustomerPortalResponse> {
		val context = authorizedWorkspaceContext.require()
		if (context.workspace.role != "OWNER") {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only workspace owners can manage a subscription")
		}
		val portal = try {
			subscriptionPortal.create(context.workspace.workspaceId)
		} catch (_: SubscriptionPortalNotAllowedException) {
			throw ApiException(
				HttpStatus.CONFLICT,
				"SUBSCRIPTION_PORTAL_UNAVAILABLE",
				"A current subscription is required to manage billing",
			)
		} catch (failure: PolarApiException) {
			throw ApiException(
				if (failure.retryable) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY,
				failure.safeCode,
				"Subscription portal could not be started",
			)
		}
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(WorkspaceCustomerPortalResponse(portal.url))
	}
}

private fun PolarCreditOverview.toResponse(checkoutAvailable: Boolean) = WorkspaceCreditOverviewResponse(
	balance = balance,
	creditedUnits = creditedUnits,
	consumedUnits = consumedUnits,
	checkoutAvailable = checkoutAvailable,
	usageEvents = usageEvents.map { event ->
		CreditUsageEventResponse(
			id = event.id,
			timestamp = event.timestamp,
			credits = event.credits,
			provider = event.provider,
			model = event.model,
		)
	},
)
