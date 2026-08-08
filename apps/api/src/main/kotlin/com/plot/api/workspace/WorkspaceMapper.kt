package com.plot.api.workspace

import com.plot.api.entitlement.EffectiveWorkspaceEntitlement
import com.plot.api.workspace.dto.WorkspaceResponse

fun Workspace.toResponse(
	entitlement: EffectiveWorkspaceEntitlement,
	role: String? = null,
): WorkspaceResponse {
	return WorkspaceResponse(
		id = id,
		name = name,
		slug = slug,
		status = status,
		logoUrl = logoUrl,
		plan = plan,
		entitlementStatus = entitlement.status,
		accessMode = entitlement.accessMode,
		trialEndsAt = trialEndsAt,
		role = role,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
