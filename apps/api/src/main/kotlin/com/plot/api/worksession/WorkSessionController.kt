package com.plot.api.worksession

import com.plot.api.routine.ChatAgentAdmissionService
import com.plot.api.routine.dto.ChatAgentRunResponse
import com.plot.api.worksession.dto.CreateWorkSessionRequest
import com.plot.api.worksession.dto.UpdateWorkSessionRequest
import com.plot.api.worksession.dto.WorkSessionResponse
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
	private val chatAgentAdmissionService: ChatAgentAdmissionService,
) {

	@GetMapping
	fun list(): List<WorkSessionResponse> {
		return workSessionService.list()
	}

	@GetMapping("/{id}/agent-runs")
	fun listAgentRuns(@PathVariable id: UUID): List<ChatAgentRunResponse> = chatAgentAdmissionService.listForSession(id)

	@PostMapping
	fun create(@RequestBody request: CreateWorkSessionRequest): WorkSessionResponse {
		return workSessionService.create(request)
	}

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@RequestBody request: UpdateWorkSessionRequest,
	): WorkSessionResponse {
		return workSessionService.update(id, request)
	}
}
