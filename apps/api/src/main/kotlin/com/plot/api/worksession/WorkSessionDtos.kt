package com.plot.api.worksession

import java.time.Instant
import java.util.UUID

data class CreateWorkSessionRequest(
	val title: String?,
)

data class UpdateWorkSessionRequest(
	val title: String?,
)

data class WorkSessionResponse(
	val id: UUID,
	val title: String?,
	val status: String,
	val lastActivityAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

fun WorkSession.toResponse(): WorkSessionResponse {
	return WorkSessionResponse(
		id = id,
		title = title,
		status = status,
		lastActivityAt = lastActivityAt,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
