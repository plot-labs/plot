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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkSessionService(
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val workSessionPersistence: WorkSessionPersistence,
) {

	@Transactional(readOnly = true)
	fun list(): List<WorkSessionResponse> {
		return workSessionPersistence
			.findRecentByWorkspaceId(devContext.devWorkspaceId)
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
			latestArtifactWorkflowRunId = null,
			lastActivityAt = now,
			createdAt = now,
			updatedAt = now,
		)

		return workSessionPersistence.insert(workSession).toResponse()
	}

	@Transactional
	fun update(id: UUID, request: UpdateWorkSessionRequest): WorkSessionResponse {
		val now = Instant.now()
		return workSessionPersistence
			.update(
				workspaceId = devContext.devWorkspaceId,
				id = id,
				title = request.title?.trim(),
				now = now,
			)
			?.toResponse()
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Work session not found")
	}

}
