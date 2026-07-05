package com.plot.api.workspace

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "workspaces")
class Workspace(
	@Id
	var id: UUID,
	var name: String,
	var slug: String,
	var createdByUserId: UUID?,
	var status: String,
	var createdAt: Instant,
	var updatedAt: Instant,
)
