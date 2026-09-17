package com.plot.api.routine

import com.plot.api.agent.AgentRunInputRequest
import com.plot.api.agent.AgentRunOrigin
import com.plot.api.agent.AgentRunSourceRequest
import com.plot.api.agent.AgentRunStatus
import com.plot.api.chat.ChatModels

import com.plot.api.content.ContentType
import java.time.Instant
import java.util.UUID

enum class RoutineExecutionTriggerKind {
	SCHEDULED, GITHUB, MANUAL,
}

enum class RoutineExecutionStatus {
	PROBING, NO_ACTIVITY, DISPATCHED, DEFERRED, FAILED,
}

data class RoutineExecutionRequest(
	val workspaceId: UUID,
	val routineId: UUID,
	val createdByUserId: UUID,
	val triggerSourceScopeId: UUID,
	val triggerKind: RoutineExecutionTriggerKind,
	val triggerKey: String,
	val requestFingerprint: String,
	val triggerDeliveryId: UUID? = null,
	val scheduledFor: Instant? = null,
	val refreshFrom: Instant? = null,
	val refreshTo: Instant? = null,
	val refreshContinuationJson: String? = null,
	val activityCursorBefore: Long? = null,
	val id: UUID? = null,
	val releaseRequestId: UUID? = null,
)

data class RoutineExecutionEvidenceRecord(
	val executionId: UUID,
	val workspaceId: UUID,
	val writingBlockId: UUID,
	val activitySequence: Long,
	val orderIndex: Int,
)

data class RoutineExecutionRecord(
	val id: UUID,
	val workspaceId: UUID,
	val routineId: UUID,
	val createdByUserId: UUID,
	val triggerSourceScopeId: UUID,
	val triggerKind: RoutineExecutionTriggerKind,
	val triggerKey: String,
	val requestFingerprint: String,
	val triggerDeliveryId: UUID?,
	val scheduledFor: Instant?,
	val refreshFrom: Instant?,
	val refreshTo: Instant?,
	val refreshContinuationJson: String?,
	val refreshCompletedAt: Instant?,
	val activityCursorBefore: Long?,
	val activityCursorAfter: Long?,
	val status: RoutineExecutionStatus,
	val attemptCount: Int,
	val transitionVersion: Long,
	val claimedBy: String?,
	val claimedAt: Instant?,
	val nextAttemptAt: Instant?,
	val errorCode: String?,
	val startedAt: Instant?,
	val finishedAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
	val releaseRequestId: UUID? = null,
)

data class RoutineContextSourceRecord(
	val id: UUID,
	val workspaceId: UUID,
	val routineId: UUID,
	val sourceScopeId: UUID,
	val orderIndex: Int,
	val createdAt: Instant,
)

data class RoutineAgentDispatchRequest(
	val instructionSnapshot: String,
	val promptVersion: String,
	val toolPolicyVersion: String,
	val budgetSnapshotJson: String = "{}",
	val maxAttempts: Int = 3,
	val sourceScopes: List<AgentRunSourceRequest>,
	val inputs: List<AgentRunInputRequest>,
	val activityCursorAfter: Long,
	val origin: AgentRunOrigin = AgentRunOrigin.ROUTINE,
	val idempotencyKey: String? = null,
	val requestFingerprint: String? = null,
	val contentType: ContentType = ContentType.CHANGELOG,
	val contentProfileRevisionId: UUID? = null,
	val contentBriefSnapshotJson: String? = null,
	val sourceSnapshotId: UUID? = null,
	val requestedModel: String = ChatModels.AUTO,
	val requestedReasoningEffort: String? = null,
)

data class RoutineExecutionSummaryRecord(
	val executionId: UUID,
	val executionStatus: RoutineExecutionStatus,
	val executionErrorCode: String?,
	val workSessionId: UUID?,
	val agentRunId: UUID?,
	val agentRunStatus: AgentRunStatus?,
	val agentFailureCode: String?,
	val artifactWorkflowRunId: UUID?,
	val artifactId: UUID?,
	val startedAt: Instant?,
	val finishedAt: Instant?,
	val releaseRequestId: UUID? = null,
)

class RoutineExecutionIdempotencyConflictException : IllegalStateException(
	"Routine execution trigger key was reused with a different fingerprint",
)

class RoutineExecutionStateException(message: String) : IllegalStateException(message)
