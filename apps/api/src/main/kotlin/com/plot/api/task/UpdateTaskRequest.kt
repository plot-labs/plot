package com.plot.api.task

import jakarta.validation.constraints.NotBlank

data class UpdateTaskRequest(
	@field:NotBlank
	val title: String,
)
