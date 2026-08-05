package com.plot.api.worksession

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import com.plot.api.worksession.dto.CreateWorkSessionRequest
import com.plot.api.worksession.dto.UpdateWorkSessionRequest
import com.plot.api.worksession.dto.WorkSessionResponse
import com.plot.api.worksession.dto.SessionGenerationResponse
import com.plot.api.artifact.dto.ArtifactSummaryResponse
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
			.findRecentByWorkspaceId(devContext.devWorkspaceId)
			.map { it.toResponse() }
	}

	@Transactional(readOnly = true)
	fun listGenerations(id: UUID): List<SessionGenerationResponse> {
		requireSession(id)
		return jdbcTemplate.query(
			"""
			select gr.id, gr.status, gr.user_instruction, gr.created_at, gr.finished_at, gr.error_code,
			       cp.id, cp.status, cp.title
			from generation_runs gr
			left join content_packs cp
			  on cp.workspace_id = gr.workspace_id and cp.generation_run_id = gr.id
			where gr.workspace_id = ? and gr.work_session_id = ?
			order by gr.created_at asc, gr.id asc
			""".trimIndent(),
			{ rs, _ ->
				val generationId = rs.getObject(1, UUID::class.java)
				val artifactId = rs.getObject(7, UUID::class.java)
				SessionGenerationResponse(
					id = generationId,
					status = rs.getString(2),
					instruction = rs.getString(3),
					createdAt = rs.getTimestamp(4).toInstant(),
					completedAt = rs.getTimestamp(5)?.toInstant(),
					failureCode = rs.getString(6),
					artifact = artifactId?.let {
						ArtifactSummaryResponse(it, generationId, rs.getString(8), rs.getString(9))
					},
				)
			},
			devContext.devWorkspaceId,
			id,
		)
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

	private fun requireSession(id: UUID): WorkSession {
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
