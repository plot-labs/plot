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
		publicCitationsEnabled = publicCitationsEnabled,
		plan = plan,
		entitlementStatus = entitlement.status,
		accessMode = entitlement.accessMode,
		subscriptionStatus = polarSubscriptionStatus,
		subscriptionCancelAtPeriodEnd = polarSubscriptionCancelAtPeriodEnd,
		subscriptionCurrentPeriodEnd = polarSubscriptionCurrentPeriodEnd,
		subscriptionEventAt = polarSubscriptionEventAt,
		capabilities = entitlement.capabilities,
		role = role,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
