package com.plot.api.writingblock

import com.plot.api.writingblock.dto.CreateWritingBlockRequest
import com.plot.api.writingblock.dto.UpdateWritingBlockRequest
import com.plot.api.writingblock.dto.WritingBlockResponse
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/blocks")
class WritingBlockController(
	private val writingBlockService: WritingBlockService,
) {

	@GetMapping
	fun list(
		@RequestParam(required = false) sourceScopeId: UUID?,
		@RequestParam(defaultValue = "0") page: Int,
		@RequestParam(defaultValue = "50") size: Int,
	): WritingBlockPageResponse {
		return writingBlockService.list(sourceScopeId, page, size)
	}

	@PostMapping
	fun create(@Valid @RequestBody request: CreateWritingBlockRequest): WritingBlockResponse {
		return writingBlockService.create(request)
	}

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): WritingBlockResponse {
		return writingBlockService.get(id)
	}

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@Valid @RequestBody request: UpdateWritingBlockRequest,
	): WritingBlockResponse {
		return writingBlockService.update(id, request)
	}
}
