package com.plot.api.generation

import com.plot.api.generation.dto.CreateGenerationRequest
import com.plot.api.generation.dto.GenerationRunResponse
import com.plot.api.generation.dto.toResponse
import com.plot.api.dev.DevContext
import com.plot.api.source.SourceManagedAccessGuard
import com.plot.api.contentpack.ContentPackService
import jakarta.validation.Valid
import java.net.URI
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.http.MediaType
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/generations")
class GenerationController(
	private val runService: GenerationRunService,
	private val accessGuard: SourceManagedAccessGuard,
	private val contentPackService: ContentPackService,
	private val eventPublisher: GenerationEventPublisher,
	private val devContext: DevContext,
) {
	@PostMapping
	fun create(
		@RequestHeader("Idempotency-Key") idempotencyKey: String,
		@Valid @RequestBody request: CreateGenerationRequest,
	): ResponseEntity<GenerationRunResponse> {
		accessGuard.requireReadable()
		val state = runService.create(
			requireNotNull(request.sourceScopeId), request.writingBlockIds, request.instruction, idempotencyKey,
		)
		return ResponseEntity.accepted()
			.location(URI.create("/api/generations/${state.runId}"))
			.cacheControl(CacheControl.noStore())
			.body(state.toResponse())
	}

	@GetMapping("/{id}")
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun get(@PathVariable id: UUID): ResponseEntity<GenerationRunResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(runService.get(id).toResponse().withTiming(id).withPack())

	@GetMapping("/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun events(@PathVariable id: UUID): SseEmitter {
		val state = runService.get(id)
		val emitter = eventPublisher.subscribe(devContext.devWorkspaceId, id)
		if (state.status in GenerationRunStatus.terminalOrPaused) {
			eventPublisher.publish(devContext.devWorkspaceId, id, state.status)
		}
		return emitter
	}

	private fun GenerationRunResponse.withTiming(runId: UUID): GenerationRunResponse =
		copy(timing = runService.getTiming(runId))

	private fun GenerationRunResponse.withPack(): GenerationRunResponse = copy(contentPack = contentPackService.findByRun(id))
}
