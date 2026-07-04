package com.plot.api.task

import jakarta.validation.constraints.NotBlank
import java.util.UUID

data class CreateTaskRequest(
	val sessionId: UUID?,
	@field:NotBlank
	val title: String,
)
