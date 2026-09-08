package com.plot.api.waitlist

import com.plot.api.waitlist.dto.WaitlistSignupRequest
import com.plot.api.waitlist.dto.WaitlistSignupResponse
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/public/waitlist")
class PublicWaitlistController(
	private val signupService: WaitlistSignupService,
) {
	@PostMapping
	fun signup(@Valid @RequestBody request: WaitlistSignupRequest): ResponseEntity<WaitlistSignupResponse> =
		ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(signupService.signup(request))
}
