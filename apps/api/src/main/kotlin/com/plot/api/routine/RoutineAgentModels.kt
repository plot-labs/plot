package com.plot.api.routine

import java.time.Instant
import java.util.UUID

enum class RoutineExecutionTriggerKind {
	SCHEDULED, GITHUB, MANUAL,
}

enum class RoutineExecutionStatus {
	PROBING, NO_ACTIVITY, DISPATCHED, FAILED,
}

enum class AgentRunStatus {
	QUEUED, RUNNING, SUCCEEDED, FAILED,
}

enum class AgentRunSourceRole {
	TRIGGER, CONTEXT,
}

enum class AgentRunInputKind {
	SEED, TOOL_RESULT,
}

enum class AgentStepKind {
	READ_TOOL, ARTIFACT_HANDOFF,
}

enum class AgentStepStatus {
	PENDING, RUNNING, SUCCEEDED, FAILED,
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
	val legacyGenerationRunId: UUID? = null,
)

data class RoutineContextSourceRecord(
	val id: UUID,
	val workspaceId: UUID,
	val routineId: UUID,
	val sourceScopeId: UUID,
	val orderIndex: Int,
	val createdAt: Instant,
)

data class AgentRunSourceRequest(
	val sourceScopeId: UUID,
	val role: AgentRunSourceRole,
	val capturedStatus: String = "ACTIVE",
	val capturedStatusChangedAt: Instant,
)

data class AgentRunInputRequest(
	val routineId: UUID?,
	val sourceScopeId: UUID,
	val writingBlockId: UUID,
	val sourceProvider: String,
	val sourceKind: String,
	val sourceLabel: String,
	val inputKind: AgentRunInputKind,
	val orderIndex: Int,
	val activitySequence: Long?,
	val snapshotTitle: String?,
	val snapshotBody: String,
	val snapshotExcerpt: String?,
	val originalUrl: String,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
	val contentHash: String,
	val capturedAt: Instant,
)

data class AgentRunDispatchRequest(
	val instructionSnapshot: String,
	val promptVersion: String,
	val toolPolicyVersion: String,
	val budgetSnapshotJson: String = "{}",
	val maxAttempts: Int = 3,
	val sourceScopes: List<AgentRunSourceRequest>,
	val inputs: List<AgentRunInputRequest>,
	val activityCursorAfter: Long,
)

data class AgentBudgetSnapshot(
	val maxModelCalls: Int,
	val maxToolCalls: Int,
	val maxRunDurationMillis: Long,
	val maxInputCharacters: Int,
	val maxEvidenceCharacters: Int,
	val truncatedSeed: Boolean = false,
)

data class AgentRunRecord(
	val id: UUID,
	val workspaceId: UUID,
	val routineExecutionId: UUID,
	val routineId: UUID,
	val createdByUserId: UUID,
	val instructionSnapshot: String,
	val promptVersion: String,
	val toolPolicyVersion: String,
	val budgetSnapshotJson: String,
	val status: AgentRunStatus,
	val currentStep: Int,
	val attemptCount: Int,
	val maxAttempts: Int,
	val modelCallCount: Int,
	val toolCallCount: Int,
	val nextAttemptAt: Instant?,
	val failureCode: String?,
	val claimedBy: String?,
	val claimedAt: Instant?,
	val transitionVersion: Long,
	val startedAt: Instant?,
	val finishedAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

data class AgentRunSourceRecord(
	val id: UUID,
	val workspaceId: UUID,
	val agentRunId: UUID,
	val sourceScopeId: UUID,
	val role: AgentRunSourceRole,
	val orderIndex: Int,
	val capturedStatus: String,
	val capturedStatusChangedAt: Instant,
	val capturedAt: Instant,
)

data class AgentRunInputRecord(
	val id: UUID,
	val workspaceId: UUID,
	val agentRunId: UUID,
	val routineId: UUID?,
	val sourceScopeId: UUID,
	val writingBlockId: UUID,
	val sourceProvider: String,
	val sourceKind: String,
	val sourceLabel: String,
	val inputKind: AgentRunInputKind,
	val orderIndex: Int,
	val activitySequence: Long?,
	val snapshotTitle: String?,
	val snapshotBody: String,
	val snapshotExcerpt: String?,
	val originalUrl: String,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
	val contentHash: String,
	val capturedAt: Instant,
)

data class AgentStepRequest(
	val agentRunId: UUID,
	val sequence: Int,
	val kind: AgentStepKind,
	val status: AgentStepStatus,
	val idempotencyKey: String,
	val toolName: String? = null,
	val argumentsJson: String = "{}",
	val resultJson: String? = null,
	val adoptedInputId: UUID? = null,
	val generationRunId: UUID? = null,
	val failureCode: String? = null,
	val startedAt: Instant? = null,
	val finishedAt: Instant? = null,
)

data class AgentStepRecord(
	val id: UUID,
	val workspaceId: UUID,
	val agentRunId: UUID,
	val sequence: Int,
	val kind: AgentStepKind,
	val status: AgentStepStatus,
	val idempotencyKey: String,
	val toolName: String?,
	val argumentsJson: String,
	val resultJson: String?,
	val adoptedInputId: UUID?,
	val generationRunId: UUID?,
	val failureCode: String?,
	val startedAt: Instant?,
	val finishedAt: Instant?,
	val createdAt: Instant,
)

data class ClaimedAgentRun(
	val workspaceId: UUID,
	val agentRunId: UUID,
	val transitionVersion: Long,
	val workerId: String,
)

data class AgentGenerationState(
	val generationRunId: UUID,
	val status: String,
	val materialized: Boolean,
)

data class RoutineExecutionSummaryRecord(
	val executionId: UUID,
	val executionStatus: RoutineExecutionStatus,
	val executionErrorCode: String?,
	val agentRunId: UUID?,
	val agentRunStatus: AgentRunStatus?,
	val agentFailureCode: String?,
	val generationRunId: UUID?,
	val artifactId: UUID?,
	val startedAt: Instant?,
	val finishedAt: Instant?,
)

class RoutineExecutionIdempotencyConflictException : IllegalStateException(
	"Routine execution trigger key was reused with a different fingerprint",
)

class RoutineExecutionStateException(message: String) : IllegalStateException(message)
class AgentRunClaimLostException : IllegalStateException("Agent run claim was lost")
class AgentRunBudgetExceededException(val safeCode: String) : IllegalStateException(safeCode)
