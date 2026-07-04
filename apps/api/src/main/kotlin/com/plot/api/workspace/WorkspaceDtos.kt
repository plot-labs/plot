package com.plot.api.workspace

import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class UpdateWorkspaceRequest(
	@field:NotBlank(message = "Workspace name must not be blank")
	val name: String,
	@field:NotBlank(message = "Workspace slug must not be blank")
	val slug: String,
)

data class WorkspaceResponse(
	val id: UUID,
	val name: String,
	val slug: String,
	val status: String,
	val createdAt: Instant,
	val updatedAt: Instant,
)

fun Workspace.toResponse(): WorkspaceResponse {
	return WorkspaceResponse(
		id = id,
		name = name,
		slug = slug,
		status = status,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
