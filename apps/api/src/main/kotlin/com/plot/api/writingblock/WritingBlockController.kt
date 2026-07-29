package com.plot.api.writingblock

import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
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

}
