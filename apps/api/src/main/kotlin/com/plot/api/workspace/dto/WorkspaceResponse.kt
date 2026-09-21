package com.plot.api.workspace.dto

import com.plot.api.entitlement.WorkspaceCapabilities
import java.time.Instant
import java.util.UUID

data class WorkspaceResponse(
	val id: UUID,
	val name: String,
	val slug: String,
	val status: String,
	val logoUrl: String?,
	val publicCitationsEnabled: Boolean,
	val plan: String,
	val entitlementStatus: String,
	val accessMode: String,
	val subscriptionStatus: String?,
	val subscriptionCancelAtPeriodEnd: Boolean?,
	val subscriptionCurrentPeriodEnd: Instant?,
	val subscriptionEventAt: Instant?,
	val capabilities: WorkspaceCapabilities,
	val trialEndsAt: Instant,
	val role: String? = null,
	val createdAt: Instant,
	val updatedAt: Instant,
	val organizationId: String? = null,
)
