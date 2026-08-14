package com.plot.api.artifact.run

import java.time.Instant
import java.util.UUID

enum class ArtifactRunStatus {
	QUEUED, WRITING, REVIEWING, REWRITING, READY, NEEDS_REVIEW, FAILED,
}

data class ArtifactRunRecord(
	val id: UUID,
	val workspaceId: UUID,
	val agentRunId: UUID,
	val createdByUserId: UUID,
	val idempotencyKey: String,
	val requestFingerprint: String,
	val status: ArtifactRunStatus,
	val errorCode: String?,
	val transitionVersion: Long,
	val startedAt: Instant?,
	val finishedAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

/** Internal projection used by AgentRun orchestration; never exposed as an API DTO. */
data class ArtifactRunWorkflowState(
	val artifactRunId: UUID,
	val agentRunId: UUID,
	val workflowRunId: UUID?,
	val status: ArtifactRunStatus,
	val errorCode: String?,
	val materialized: Boolean,
)

class ArtifactRunIdempotencyConflictException : IllegalStateException(
	"Artifact run idempotency key was reused with different inputs",
)
