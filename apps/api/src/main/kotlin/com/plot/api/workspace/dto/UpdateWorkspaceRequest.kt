package com.plot.api.workspace.dto

import jakarta.validation.constraints.Size

data class UpdateWorkspaceRequest(
	@field:Size(max = 80)
	val name: String? = null,
	@field:Size(max = 600_000)
	val logoUrl: String? = null,
)
