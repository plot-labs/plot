package com.plot.api.waitlist.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class WaitlistSignupRequest(
	@field:NotBlank
	@field:Email
	@field:Size(max = 254)
	val email: String?,
	@field:Size(max = 32)
	val role: String? = null,
	@field:NotBlank
	@field:Size(max = 64)
	val painChannel: String?,
	@field:Size(max = 200)
	val company: String? = null,
	@field:Size(max = 200)
	val website: String? = null,
	@field:Size(max = 128)
	val resendContactId: String? = null,
)

data class WaitlistSignupResponse(
	val id: UUID?,
	val duplicate: Boolean = false,
)
