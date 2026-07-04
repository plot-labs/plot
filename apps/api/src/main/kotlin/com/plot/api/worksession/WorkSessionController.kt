package com.plot.api.worksession

import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sessions")
class WorkSessionController(
	private val workSessionService: WorkSessionService,
) {

	@GetMapping
	fun list(): List<WorkSessionResponse> {
		return workSessionService.list()
	}

	@PostMapping
	fun create(@RequestBody request: CreateWorkSessionRequest): WorkSessionResponse {
		return workSessionService.create(request)
	}

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): WorkSessionResponse {
		return workSessionService.get(id)
	}

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@RequestBody request: UpdateWorkSessionRequest,
	): WorkSessionResponse {
		return workSessionService.update(id, request)
	}
}
