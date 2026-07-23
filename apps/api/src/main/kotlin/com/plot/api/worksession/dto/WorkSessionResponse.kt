package com.plot.api.worksession.dto

import java.time.Instant
import java.util.UUID

data class WorkSessionResponse(
	val id: UUID,
	val title: String?,
	val status: String,
	val latestGenerationId: UUID?,
	val lastActivityAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)
