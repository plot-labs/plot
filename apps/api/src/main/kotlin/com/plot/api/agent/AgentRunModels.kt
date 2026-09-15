package com.plot.api.agent

import com.plot.api.content.ContentType
import java.time.Instant
import java.util.UUID

enum class AgentRunStatus {
	QUEUED, RUNNING, SUCCEEDED, FAILED,
}

enum class AgentRunOrigin {
	ROUTINE, CHAT,
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
	val routineExecutionId: UUID?,
	val workSessionId: UUID?,
	val routineId: UUID?,
	val origin: AgentRunOrigin,
	val idempotencyKey: String,
	val requestFingerprint: String,
	val createdByUserId: UUID,
	val instructionSnapshot: String,
	val skillsSnapshotJson: String = "[]",
	val promptVersion: String,
	val toolPolicyVersion: String,
	val budgetSnapshotJson: String,
	val contentType: ContentType = ContentType.CHANGELOG,
	val contentProfileRevisionId: UUID? = null,
	val contentBriefSnapshotJson: String? = null,
	val sourceSnapshotId: UUID? = null,
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
	val displayName: String?,
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
	val artifactWorkflowRunId: UUID? = null,
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
	val artifactWorkflowRunId: UUID?,
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

class AgentRunIdempotencyConflictException : IllegalStateException(
	"Agent run idempotency key was reused with a different fingerprint",
)

class AgentRunClaimLostException : IllegalStateException("Agent run claim was lost")
class AgentRunBudgetExceededException(val safeCode: String) : IllegalStateException(safeCode)
