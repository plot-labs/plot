package com.plot.api.task

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tasks")
class Task(
	@Id
	var id: UUID,
	var workspaceId: UUID,
	var workSessionId: UUID?,
	var title: String,
	var status: String,
	var createdByUserId: UUID?,
	var lastActivityAt: Instant?,
	var createdAt: Instant,
	var updatedAt: Instant,
)
