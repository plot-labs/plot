package com.plot.api.billing

import com.plot.api.auth.AuthorizedWorkspaceContext
import com.plot.api.common.ApiException
import java.time.Instant
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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
)

@RestController
@RequestMapping("/api/billing")
class CreditController(
	private val credits: PolarCreditService,
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
			.body(overview.toResponse())
	}
}

private fun PolarCreditOverview.toResponse() = WorkspaceCreditOverviewResponse(
	balance = balance,
	creditedUnits = creditedUnits,
	consumedUnits = consumedUnits,
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
