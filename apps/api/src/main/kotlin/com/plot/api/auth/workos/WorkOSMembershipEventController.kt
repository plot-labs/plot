package com.plot.api.auth.workos

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class WorkOSMembershipEventResponse(val disposition: WorkOSMembershipEventDisposition)

@RestController
@RequestMapping("/api/workos")
class WorkOSMembershipEventController(
	private val eventService: WorkOSMembershipEventService,
) {
	@PostMapping("/webhook", consumes = ["application/json"])
	fun receive(
		@RequestHeader("WorkOS-Signature", required = false) signature: String?,
		@RequestBody rawBody: String,
	): ResponseEntity<WorkOSMembershipEventResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(WorkOSMembershipEventResponse(eventService.handle(rawBody, signature)))
}
