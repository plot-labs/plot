package com.plot.api.task

import com.plot.api.task.dto.TaskResponse

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
