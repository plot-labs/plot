package com.plot.api.routine

import com.plot.api.common.ApiException
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
	private val persistence: RoutinePersistence,
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
		sourceManagedAccessGuard.requireReadable()
		val current = get(id)
		val sourceScopeId = request.sourceScopeId ?: current.sourceScopeId
		requireGitHubScope(sourceScopeId)
		val name = request.name?.trim()?.takeUnless { it.isNullOrBlank() } ?: current.name
		val instruction = request.instruction?.trim()?.takeUnless { it.isNullOrBlank() } ?: current.instruction
		return persistence.update(
			workspaceId = current.workspaceId,
			id = current.id,
			name = name,
			sourceScopeId = sourceScopeId,
			instruction = instruction,
			cadence = request.cadence ?: current.cadence,
			enabled = request.enabled ?: current.enabled,
		)
	}

	@Transactional
	fun queueNow(id: UUID): RoutineRecord = persistence.queueNow(devContext.devWorkspaceId, id)
		?: throw notFound()

	private fun requireGitHubScope(id: UUID): SourceScope {
		val scope = sourceScopeRepository.findByWorkspaceIdAndId(devContext.devWorkspaceId, id)
			?: throw notFound()
		if (scope.status != "ACTIVE" || scope.provider != "GITHUB") {
			throw ApiException(HttpStatus.CONFLICT, "SOURCE_NOT_READY", "Connect an active GitHub source before creating a routine")
		}
		return scope
	}

	private fun notFound() = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Routine not found")
}
