package com.plot.api.timeline.dto

import java.time.Instant
import java.util.UUID

data class ExecutionTimelineItem(
	val id: UUID,
	val workspaceId: UUID,
	val origin: String,
	val stage: String,
	val status: String,
	val statusLabel: String,
	val deliveryId: UUID?,
	val releaseRequestId: UUID?,
	val routineId: UUID?,
	val routineExecutionId: UUID?,
	val agentRunId: UUID?,
	val artifactWorkflowRunId: UUID?,
	val artifactId: UUID?,
	val workSessionId: UUID?,
	val attemptCount: Int,
	val maxAttempts: Int?,
	val nextAttemptAt: Instant?,
	val safeErrorCode: String?,
	val recoveryAction: String?,
	val createdAt: Instant,
	val updatedAt: Instant,
	val finishedAt: Instant?,
)
