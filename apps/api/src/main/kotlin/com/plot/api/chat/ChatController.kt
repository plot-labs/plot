package com.plot.api.chat

import com.plot.api.chat.dto.ChatAgentRunResponse
import com.plot.api.chat.dto.ChatResponseVersionDto
import com.plot.api.chat.dto.ChatTurnDto
import com.plot.api.chat.dto.CreateChatAgentRunRequest
import com.plot.api.chat.dto.RetryEligibilityDto
import com.plot.api.config.PlotAiProperties
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agent-runs")
class ChatController(
	private val runs: ChatRunService,
	private val queries: ChatQueryService,
	private val aiProperties: PlotAiProperties,
) {
	@PostMapping
	fun create(
		@RequestHeader("Idempotency-Key") idempotencyKey: String,
		@Valid @RequestBody request: CreateChatAgentRunRequest,
	): ResponseEntity<ChatAgentRunResponse> {
		val response = runs.admit(request, idempotencyKey)
		return ResponseEntity.accepted()
			.location(URI.create("/api/agent-runs/${response.id}"))
			.cacheControl(CacheControl.noStore())
			.body(response)
	}

	@GetMapping("/models")
	fun listModelCapabilities(): ResponseEntity<List<ChatModelCapability>> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(ChatModels.capabilities(aiProperties))

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): ResponseEntity<ChatAgentRunResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(queries.getRun(id))

	@GetMapping("/versions/{versionId}")
	fun getResponseVersion(@PathVariable versionId: UUID): ResponseEntity<ChatResponseVersionDto> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(queries.getVersion(versionId))

	@GetMapping("/versions/{versionId}/eligibility")
	fun getRetryEligibility(@PathVariable versionId: UUID): ResponseEntity<RetryEligibilityDto> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(queries.getVersion(versionId).retryEligibility)

	@GetMapping("/sessions/{sessionId}/turns")
	fun listTurns(
		@PathVariable sessionId: UUID,
		@RequestParam(required = false) selectedVersionId: UUID? = null,
	): ResponseEntity<List<ChatTurnDto>> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(queries.listTurnsForSession(sessionId, selectedVersionId))

	@PostMapping("/versions/{versionId}/retry")
	fun retry(
		@PathVariable versionId: UUID,
		@RequestHeader("Idempotency-Key") idempotencyKey: String,
	): ResponseEntity<ChatResponseVersionDto> {
		val response = runs.retry(versionId, idempotencyKey)
		return ResponseEntity.accepted()
			.location(URI.create("/api/agent-runs/versions/${response.id}"))
			.cacheControl(CacheControl.noStore())
			.body(response)
	}
}
