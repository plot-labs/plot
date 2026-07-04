package com.plot.api.task

import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/tasks")
class TaskController(
	private val taskService: TaskService,
) {

	@GetMapping
	fun list(): List<TaskResponse> {
		return taskService.list()
	}

	@PostMapping
	fun create(@Valid @RequestBody request: CreateTaskRequest): TaskResponse {
		return taskService.create(request)
	}

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): TaskResponse {
		return taskService.get(id)
	}

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@Valid @RequestBody request: UpdateTaskRequest,
	): TaskResponse {
		return taskService.update(id, request)
	}
}
