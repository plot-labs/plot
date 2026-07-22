package com.plot.api.auth

import com.plot.api.common.ApiException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/account")
class AccountController(
	private val bootstrapService: AccountBootstrapService,
) {
	@PostMapping("/bootstrap")
	fun bootstrap(@AuthenticationPrincipal jwt: Jwt?): ResponseEntity<BootstrapAccountResponse> = ResponseEntity
		.ok()
		.cacheControl(CacheControl.noStore())
		.body(bootstrapService.bootstrap(jwt ?: throw ApiException(
			HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required",
		)))
}
