package com.plot.api.task

import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class CreateTaskRequest(
	val sessionId: UUID?,
	@field:NotBlank
	val title: String,
)

data class UpdateTaskRequest(
	@field:NotBlank
	val title: String,
)

data class TaskResponse(
	val id: UUID,
	val sessionId: UUID?,
	val title: String,
	val status: String,
	val lastActivityAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

fun Task.toResponse(): TaskResponse {
	return TaskResponse(
		id = id,
		sessionId = workSessionId,
		title = title,
		status = status,
		lastActivityAt = lastActivityAt,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
