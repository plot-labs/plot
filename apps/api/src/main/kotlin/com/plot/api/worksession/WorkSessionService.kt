package com.plot.api.worksession

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import com.plot.api.worksession.dto.CreateWorkSessionRequest
import com.plot.api.worksession.dto.UpdateWorkSessionRequest
import com.plot.api.worksession.dto.WorkSessionResponse
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkSessionService(
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val workSessionRepository: WorkSessionRepository,
	private val jdbcTemplate: JdbcTemplate,
) {

	@Transactional(readOnly = true)
	fun list(): List<WorkSessionResponse> {
		return workSessionRepository
			.findAllByWorkspaceIdOrderByCreatedAtDesc(devContext.devWorkspaceId)
			.map { it.toResponse() }
	}

	@Transactional
	fun create(request: CreateWorkSessionRequest): WorkSessionResponse {
		val now = Instant.now()
		val workSession = WorkSession(
			id = uuidGenerator.next(),
			workspaceId = devContext.devWorkspaceId,
			title = request.title?.trim(),
			status = "OPEN",
			createdByUserId = devContext.devUserId,
			latestGenerationRunId = null,
			lastActivityAt = now,
			createdAt = now,
			updatedAt = now,
		)

		return workSessionRepository.save(workSession).toResponse()
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): WorkSessionResponse {
		return requireSession(id).toResponse()
	}

	@Transactional
	fun update(id: UUID, request: UpdateWorkSessionRequest): WorkSessionResponse {
		val workSession = requireSession(id)
		val now = Instant.now()

		if (request.title != null) workSession.title = request.title.trim()
		if (request.latestGenerationId != null) {
			requireGenerationInWorkspace(request.latestGenerationId)
			workSession.latestGenerationRunId = request.latestGenerationId
		}
		workSession.lastActivityAt = now
		workSession.updatedAt = now

		return workSession.toResponse()
	}

	@Transactional(readOnly = true)
	fun requireSession(id: UUID): WorkSession {
		return workSessionRepository.findByWorkspaceIdAndId(devContext.devWorkspaceId, id)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Work session not found")
	}

	private fun requireGenerationInWorkspace(id: UUID) {
		val exists = jdbcTemplate.queryForObject(
			"select exists(select 1 from generation_runs where workspace_id = ? and id = ?)",
			Boolean::class.java,
			devContext.devWorkspaceId,
			id,
		) ?: false
		if (!exists) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_GENERATION", "Generation run is unavailable in this workspace")
	}
}
