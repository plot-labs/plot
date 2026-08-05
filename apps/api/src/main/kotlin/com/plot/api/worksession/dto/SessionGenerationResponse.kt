package com.plot.api.worksession.dto

import com.plot.api.artifact.dto.ArtifactSummaryResponse
import java.time.Instant
import java.util.UUID

data class SessionGenerationResponse(
	val id: UUID,
	val status: String,
	val instruction: String?,
	val createdAt: Instant,
	val completedAt: Instant?,
	val failureCode: String?,
	val artifact: ArtifactSummaryResponse?,
)
