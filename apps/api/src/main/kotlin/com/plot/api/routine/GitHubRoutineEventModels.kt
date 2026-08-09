package com.plot.api.routine

import java.time.Instant
import java.util.UUID

enum class GitHubRoutineEventStatus {
	QUEUED, PROCESSING, SUCCEEDED, FAILED,
}

data class GitHubRoutineEventEvidence(
	val writingBlockId: UUID,
	val activitySequence: Long,
)

data class GitHubRoutineEventRun(
	val id: UUID,
	val workspaceId: UUID,
	val routineId: UUID,
	val deliveryId: UUID,
	val externalDeliveryId: String,
	val receivedAt: Instant,
	val status: GitHubRoutineEventStatus,
	val attemptCount: Int,
	val transitionVersion: Long,
	val generationRunId: UUID?,
	val errorCode: String?,
	val claimedBy: String?,
	val claimedAt: Instant?,
	val evidence: List<GitHubRoutineEventEvidence>,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	val writingBlockIds: List<UUID> get() = evidence.map { it.writingBlockId }
}

class GitHubRoutineEventClaimLostException : IllegalStateException("GitHub routine event claim was lost")

class GitHubRoutineEventPermanentException(
	val safeErrorCode: String,
) : IllegalStateException(safeErrorCode)
