package com.plot.api.worksession

import com.plot.api.worksession.dto.WorkSessionResponse

fun WorkSession.toResponse(): WorkSessionResponse {
	return WorkSessionResponse(
		id = id,
		title = title,
		status = status,
		latestGenerationId = latestGenerationRunId,
		lastActivityAt = lastActivityAt,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
