package com.plot.api.workspace.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateWorkspaceRequest(
	@field:NotBlank(message = "Workspace name is required")
	@field:Size(max = 80)
	val name: String,
)
