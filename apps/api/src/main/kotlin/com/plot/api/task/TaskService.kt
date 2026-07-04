package com.plot.api.task

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import com.plot.api.task.dto.CreateTaskRequest
import com.plot.api.task.dto.TaskResponse
import com.plot.api.task.dto.UpdateTaskRequest
import com.plot.api.worksession.WorkSessionService
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TaskService(
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val taskRepository: TaskRepository,
	private val workSessionService: WorkSessionService,
) {

	@Transactional(readOnly = true)
	fun list(): List<TaskResponse> {
		return taskRepository
			.findAllByWorkspaceIdOrderByCreatedAtDesc(devContext.devWorkspaceId)
			.map { it.toResponse() }
	}

	@Transactional
	fun create(request: CreateTaskRequest): TaskResponse {
		val sessionId = request.sessionId
		if (sessionId != null) {
			requireCreatableSession(sessionId)
		}

		val now = Instant.now()
		val task = Task(
			id = uuidGenerator.next(),
			workspaceId = devContext.devWorkspaceId,
			workSessionId = sessionId,
			title = request.title.trim(),
			status = "QUEUED",
			createdByUserId = devContext.devUserId,
			lastActivityAt = now,
			createdAt = now,
			updatedAt = now,
		)

		return taskRepository.save(task).toResponse()
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): TaskResponse {
		return requireTask(id).toResponse()
	}

	@Transactional
	fun update(id: UUID, request: UpdateTaskRequest): TaskResponse {
		val task = requireTask(id)
		val now = Instant.now()

		task.title = request.title.trim()
		task.lastActivityAt = now
		task.updatedAt = now

		return task.toResponse()
	}

	private fun requireTask(id: UUID): Task {
		return taskRepository.findByWorkspaceIdAndId(devContext.devWorkspaceId, id)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Task not found")
	}

	private fun requireCreatableSession(id: UUID) {
		try {
			workSessionService.requireSession(id)
		} catch (exception: ApiException) {
			throw ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Work session not found")
		}
	}
}
