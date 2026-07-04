package com.plot.api.workspace

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
