package com.plot.api.routine

import com.plot.api.routine.dto.CreateRoutineRequest
import com.plot.api.routine.dto.RoutineResponse
import com.plot.api.routine.dto.UpdateRoutineRequest
import com.plot.api.routine.dto.toResponse
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/routines")
class RoutineController(
	private val service: RoutineService,
	private val worker: RoutineWorker,
) {
	@GetMapping
	fun list(): List<RoutineResponse> = service.list().map { it.toResponse() }

	@PostMapping
	fun create(@Valid @RequestBody request: CreateRoutineRequest): ResponseEntity<RoutineResponse> = ResponseEntity
		.status(201)
		.body(service.create(request).toResponse())

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@Valid @RequestBody request: UpdateRoutineRequest,
	): RoutineResponse = service.update(id, request).toResponse()

	@PostMapping("/{id}/run")
	fun runNow(@PathVariable id: UUID): RoutineResponse {
		val queued = service.queueNow(id)
		worker.runNow(queued.workspaceId, id)
		return service.get(id).toResponse()
	}
}
