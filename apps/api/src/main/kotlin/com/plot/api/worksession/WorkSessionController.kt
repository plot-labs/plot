package com.plot.api.worksession

import com.plot.api.chat.ChatQueryService
import com.plot.api.chat.dto.ChatAgentRunResponse
import com.plot.api.worksession.dto.WorkSessionResponse
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sessions")
class WorkSessionController(
	private val workSessionService: WorkSessionService,
	private val chatQueries: ChatQueryService,
) {

	@GetMapping
	fun list(): List<WorkSessionResponse> {
		return workSessionService.list()
	}

	@GetMapping("/{id}/agent-runs")
	fun listAgentRuns(@PathVariable id: UUID): List<ChatAgentRunResponse> = chatQueries.listForSession(id)

	@GetMapping("/{id}/turns")
	fun listTurns(
		@PathVariable id: UUID,
		@org.springframework.web.bind.annotation.RequestParam(required = false) selectedVersionId: UUID? = null,
	): List<com.plot.api.chat.dto.ChatTurnDto> = chatQueries.listTurnsForSession(id, selectedVersionId)

}
