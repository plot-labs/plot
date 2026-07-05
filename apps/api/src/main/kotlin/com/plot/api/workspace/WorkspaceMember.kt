package com.plot.api.workspace

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "workspace_members")
class WorkspaceMember(
	@Id
	var id: UUID,
	var workspaceId: UUID,
	var userId: UUID,
	var role: String,
	var status: String,
	var joinedAt: Instant,
	var createdAt: Instant,
	var updatedAt: Instant,
)
