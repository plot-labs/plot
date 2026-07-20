package com.plot.api.generation

import com.plot.api.generation.dto.CreateGenerationRequest
import com.plot.api.generation.dto.GenerationRunResponse
import com.plot.api.generation.dto.toResponse
import com.plot.api.source.SourceManagedAccessGuard
import com.plot.api.contentpack.ContentPackService
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
@RequestMapping("/api/generations")
class GenerationController(
	private val runService: GenerationRunService,
	private val accessGuard: SourceManagedAccessGuard,
	private val contentPackService: ContentPackService,
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
			.body(state.toResponse().withPack())
	}

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): ResponseEntity<GenerationRunResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(runService.get(id).toResponse().withPack())

	private fun GenerationRunResponse.withPack(): GenerationRunResponse = copy(contentPack = contentPackService.findByRun(id))
}
