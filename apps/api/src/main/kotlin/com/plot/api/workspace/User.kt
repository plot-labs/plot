package com.plot.api.workspace

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
	@Id
	var id: UUID,
	var email: String,
	var displayName: String,
	var status: String,
	var createdAt: Instant,
	var updatedAt: Instant,
)
