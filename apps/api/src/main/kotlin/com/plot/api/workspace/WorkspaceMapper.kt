package com.plot.api.workspace

import com.plot.api.workspace.dto.WorkspaceResponse

fun Workspace.toResponse(role: String? = null): WorkspaceResponse {
	return WorkspaceResponse(
		id = id,
		name = name,
		slug = slug,
		status = status,
		role = role,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
