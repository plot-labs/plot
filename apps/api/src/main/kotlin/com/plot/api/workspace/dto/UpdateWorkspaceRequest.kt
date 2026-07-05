package com.plot.api.workspace.dto

import jakarta.validation.constraints.NotBlank

data class UpdateWorkspaceRequest(
	@field:NotBlank(message = "Workspace name must not be blank")
	val name: String,
	@field:NotBlank(message = "Workspace slug must not be blank")
	val slug: String,
)
