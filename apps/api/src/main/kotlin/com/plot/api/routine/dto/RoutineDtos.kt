package com.plot.api.routine.dto

import com.plot.api.routine.RoutineCadence
import com.plot.api.routine.RoutineRecord
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateRoutineRequest(
	@field:NotBlank @field:Size(max = 80) val name: String,
	@field:NotNull val sourceScopeId: UUID?,
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
	val createdAt: Instant,
	val updatedAt: Instant,
)

fun RoutineRecord.toResponse() = RoutineResponse(
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
	createdAt = createdAt,
	updatedAt = updatedAt,
)
