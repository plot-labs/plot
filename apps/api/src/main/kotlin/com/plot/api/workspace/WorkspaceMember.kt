package com.plot.api.workspace

import java.time.Instant
import java.util.UUID

class WorkspaceMember(
	var id: UUID,
	var workspaceId: UUID,
	var userId: UUID,
	var role: String,
	var status: String,
	var joinedAt: Instant,
	var createdAt: Instant,
	var updatedAt: Instant,
)
