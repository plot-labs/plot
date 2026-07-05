package com.plot.api.workspace

import com.plot.api.workspace.dto.WorkspaceResponse

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
