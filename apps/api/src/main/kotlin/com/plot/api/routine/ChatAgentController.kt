package com.plot.api.routine

import com.plot.api.routine.dto.ChatAgentRunResponse
import com.plot.api.routine.dto.CreateChatAgentRunRequest
import jakarta.validation.Valid
import java.net.URI
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agent-runs")
class ChatAgentController(
	private val service: ChatAgentAdmissionService,
) {
	@PostMapping
	fun create(
		@RequestHeader("Idempotency-Key") idempotencyKey: String,
		@Valid @RequestBody request: CreateChatAgentRunRequest,
	): ResponseEntity<ChatAgentRunResponse> {
		val response = service.admit(request, idempotencyKey)
		return ResponseEntity.accepted()
			.location(URI.create("/api/agent-runs/${response.id}"))
			.cacheControl(CacheControl.noStore())
			.body(response)
	}

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): ResponseEntity<ChatAgentRunResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(service.get(id))
}
