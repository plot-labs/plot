package com.plot.api.routine

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import com.plot.api.source.SourceManagedAccessGuard
import com.plot.api.source.SourceScope
import com.plot.api.source.SourceScopeRepository
import com.plot.api.routine.dto.CreateRoutineRequest
import com.plot.api.routine.dto.UpdateRoutineRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RoutineService(
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val persistence: RoutinePersistence,
	private val agentPersistence: RoutineAgentPersistence,
	private val sourceScopeRepository: SourceScopeRepository,
	private val sourceManagedAccessGuard: SourceManagedAccessGuard,
) {
	@Transactional(readOnly = true)
	fun list(): List<RoutineRecord> = persistence.list(devContext.devWorkspaceId)

	@Transactional(readOnly = true)
	fun get(id: UUID): RoutineRecord = persistence.find(devContext.devWorkspaceId, id) ?: throw notFound()

	@Transactional
	fun create(request: CreateRoutineRequest): RoutineRecord {
		sourceManagedAccessGuard.requireReadable()
		val sourceScopeId = requireNotNull(request.sourceScopeId)
		val cadence = requireNotNull(request.cadence)
		val scope = requireGitHubScope(sourceScopeId)
		return persistence.insert(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			name = request.name.trim(),
			sourceScopeId = scope.id,
			instruction = request.instruction.trim(),
			cadence = cadence,
		)
	}

	@Transactional
	fun update(id: UUID, request: UpdateRoutineRequest): RoutineRecord {
		val current = get(id)
		val enabled = requireNotNull(request.enabled)
		if (enabled) {
			sourceManagedAccessGuard.requireReadable()
			requireGitHubScope(current.sourceScopeId)
		}
		return persistence.updateEnabled(
			workspaceId = current.workspaceId,
			id = current.id,
			enabled = enabled,
		) ?: throw busy()
	}

	@Transactional
	fun queueNow(id: UUID, idempotencyKey: String): RoutineRecord {
		val routine = get(id)
		val key = idempotencyKey.trim()
		if (key.isBlank() || key.length > 200) {
			throw ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required")
		}
		val request = RoutineExecutionRequest(
			workspaceId = routine.workspaceId,
			routineId = routine.id,
			createdByUserId = devContext.devUserId,
			triggerSourceScopeId = routine.sourceScopeId,
			triggerKind = RoutineExecutionTriggerKind.MANUAL,
			triggerKey = "manual:${routine.id}:$key",
			requestFingerprint = manualFingerprint(routine),
			activityCursorBefore = routine.activityCursorSequence,
		)
		val existing = agentPersistence.findByTriggerKey(routine.workspaceId, routine.id, request.triggerKey)
		if (existing != null && existing.requestFingerprint != request.requestFingerprint) {
			throw RoutineExecutionIdempotencyConflictException()
		}
		if (existing != null && existing.status != RoutineExecutionStatus.PROBING) {
			// A replay of a terminal execution is already converged. Never put its
			// id back into the legacy Routine claim projection.
			if (routine.activeExecutionId == existing.id) {
				agentPersistence.projectRoutine(
					workspaceId = routine.workspaceId,
					routineId = routine.id,
					executionId = existing.id,
					now = existing.finishedAt ?: existing.updatedAt,
					nextRunAt = routine.nextRunAt,
					status = existing.publicRoutineStatus(),
					errorCode = existing.errorCode,
				)
			}
			return persistence.find(routine.workspaceId, routine.id) ?: routine
		}
		if (existing != null && routine.activeExecutionId == existing.id) {
			// A concurrent retry observes the same in-flight execution. The current
			// claimant owns the work; the caller can safely read the projection.
			return routine
		}
		val executionId = existing?.id ?: uuidGenerator.next()
		val queued = persistence.queueManual(devContext.devWorkspaceId, id, executionId)
			?: run {
				// The routine row is the serialization point for different manual keys.
				// If an identical request raced us, its canonical execution is now
				// visible and is safe to converge on; a different key remains busy.
				val raced = agentPersistence.findByTriggerKey(routine.workspaceId, routine.id, request.triggerKey)
				if (raced != null && raced.requestFingerprint == request.requestFingerprint && raced.status != RoutineExecutionStatus.PROBING) {
					return persistence.find(routine.workspaceId, routine.id) ?: routine
				}
				if (raced != null && raced.requestFingerprint == request.requestFingerprint && persistence.find(routine.workspaceId, routine.id)?.activeExecutionId == raced.id) {
					return persistence.find(routine.workspaceId, routine.id) ?: routine
				}
				throw busy()
			}
		agentPersistence.createExecution(request.copy(id = executionId))
		return queued
	}

	private fun requireGitHubScope(id: UUID): SourceScope {
		val scope = sourceScopeRepository.findByWorkspaceIdAndId(devContext.devWorkspaceId, id)
			?: throw notFound()
		if (scope.status != "ACTIVE" || scope.provider != "GITHUB") {
			throw ApiException(HttpStatus.CONFLICT, "SOURCE_NOT_READY", "Connect an active GitHub source before creating a routine")
		}
		return scope
	}

	private fun notFound() = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Routine not found")

	private fun busy() = ApiException(HttpStatus.CONFLICT, "ROUTINE_BUSY", "Routine is already running")

	private fun manualFingerprint(routine: RoutineRecord): String = buildString {
		append(routine.id).append('|')
		append(routine.sourceScopeId).append('|')
		append(routine.cadence.name).append('|')
		append(routine.instruction).append('|')
		append("routine-agent-v1").append('|')
		append("read-only-v1")
		agentPersistence.listContextSources(routine.workspaceId, routine.id)
			.forEach { append('|').append(it.sourceScopeId) }
	}

	private fun RoutineExecutionRecord.publicRoutineStatus(): String = when (status) {
		RoutineExecutionStatus.DISPATCHED -> "QUEUED"
		RoutineExecutionStatus.NO_ACTIVITY -> "NO_ACTIVITY"
		RoutineExecutionStatus.FAILED -> "FAILED"
		RoutineExecutionStatus.PROBING -> "QUEUED"
	}
}
