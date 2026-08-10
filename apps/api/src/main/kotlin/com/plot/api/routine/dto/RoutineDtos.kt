package com.plot.api.routine.dto

import com.plot.api.routine.AgentRunRecord
import com.plot.api.routine.AgentStepRecord
import com.plot.api.routine.RoutineCadence
import com.plot.api.routine.RoutineExecutionSummaryRecord
import com.plot.api.routine.RoutineRecord
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateRoutineRequest(
	@field:NotBlank @field:Size(max = 80) val name: String,
	@field:NotNull val sourceScopeId: UUID?,
	@field:Size(max = 4) val contextSourceScopeIds: List<UUID> = emptyList(),
	@field:NotBlank @field:Size(max = 2_000) val instruction: String,
	@field:NotNull val cadence: RoutineCadence?,
)

data class UpdateRoutineRequest(
	@field:NotNull val enabled: Boolean?,
)

data class RoutineResponse(
	val id: UUID,
	val name: String,
	val sourceScopeId: UUID,
	val sourceLabel: String,
	val instruction: String,
	val cadence: RoutineCadence,
	val enabled: Boolean,
	val lastRunAt: Instant?,
	val nextRunAt: Instant,
	val lastGenerationRunId: UUID?,
	val lastRunStatus: String?,
	val lastErrorCode: String?,
	val contextSourceScopeIds: List<UUID>,
	val latestExecution: RoutineExecutionSummaryResponse?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

data class RoutineExecutionSummaryResponse(
	val id: UUID,
	val status: String,
	val chatId: UUID?,
	val agentRunId: UUID?,
	val agentRunStatus: String?,
	val generationRunId: UUID?,
	val artifactId: UUID?,
	val errorCode: String?,
	val startedAt: Instant?,
	val finishedAt: Instant?,
)

data class AgentRunDetailResponse(
	val id: UUID,
	val routineExecutionId: UUID,
	val routineId: UUID,
	val chatId: UUID?,
	val status: String,
	val failureCode: String?,
	val generationRunId: UUID?,
	val artifactId: UUID?,
	val startedAt: Instant?,
	val finishedAt: Instant?,
	val steps: List<AgentStepResponse>,
)

data class AgentStepResponse(
	val sequence: Int,
	val kind: String,
	val status: String,
	val toolName: String?,
	val failureCode: String?,
	val generationRunId: UUID?,
	val artifactId: UUID?,
	val startedAt: Instant?,
	val finishedAt: Instant?,
)

fun RoutineRecord.toResponse(
	contextSourceScopeIds: List<UUID> = emptyList(),
	latestExecution: RoutineExecutionSummaryRecord? = null,
) = RoutineResponse(
	id = id,
	name = name,
	sourceScopeId = sourceScopeId,
	sourceLabel = sourceLabel,
	instruction = instruction,
	cadence = cadence,
	enabled = enabled,
	lastRunAt = lastRunAt,
	nextRunAt = nextRunAt,
	lastGenerationRunId = lastGenerationRunId,
	lastRunStatus = lastRunStatus,
	lastErrorCode = lastErrorCode,
	contextSourceScopeIds = contextSourceScopeIds,
	latestExecution = latestExecution?.toResponse(),
	createdAt = createdAt,
	updatedAt = updatedAt,
)

fun RoutineExecutionSummaryRecord.toResponse() = RoutineExecutionSummaryResponse(
	id = executionId,
	status = executionStatus.name,
	chatId = workSessionId,
	agentRunId = agentRunId,
	agentRunStatus = agentRunStatus?.name,
	generationRunId = generationRunId,
	artifactId = artifactId,
	errorCode = agentFailureCode ?: executionErrorCode,
	startedAt = startedAt,
	finishedAt = finishedAt,
)

fun AgentRunRecord.toDetailResponse(
	steps: List<AgentStepRecord>,
	artifactIds: Map<UUID, UUID>,
): AgentRunDetailResponse {
	val generationRunId = steps.lastOrNull { it.generationRunId != null }?.generationRunId
	return AgentRunDetailResponse(
		id = id,
		routineExecutionId = routineExecutionId,
		routineId = routineId,
		chatId = workSessionId,
		status = status.name,
		failureCode = failureCode,
		generationRunId = generationRunId,
		artifactId = generationRunId?.let(artifactIds::get),
		startedAt = startedAt,
		finishedAt = finishedAt,
		steps = steps.map { step ->
			AgentStepResponse(
				sequence = step.sequence,
				kind = step.kind.name,
				status = step.status.name,
				toolName = step.toolName,
				failureCode = step.failureCode,
				generationRunId = step.generationRunId,
				artifactId = step.generationRunId?.let(artifactIds::get),
				startedAt = step.startedAt,
				finishedAt = step.finishedAt,
			)
		},
	)
}
