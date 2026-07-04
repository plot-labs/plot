package com.plot.api.worksession

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
