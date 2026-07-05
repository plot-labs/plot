package com.plot.api.task.dto

import java.time.Instant
import java.util.UUID

data class TaskResponse(
	val id: UUID,
	val sessionId: UUID?,
	val title: String,
	val status: String,
	val lastActivityAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)
