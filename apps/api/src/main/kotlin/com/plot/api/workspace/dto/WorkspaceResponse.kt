package com.plot.api.workspace.dto

import java.time.Instant
import java.util.UUID

data class WorkspaceResponse(
	val id: UUID,
	val name: String,
	val slug: String,
	val status: String,
	val plan: String,
	val entitlementStatus: String,
	val accessMode: String,
	val trialEndsAt: Instant,
	val role: String? = null,
	val createdAt: Instant,
	val updatedAt: Instant,
)
