package com.plot.api.changelog

import com.plot.api.changelog.dto.PublicChangelogEntryDetailResponse
import com.plot.api.changelog.dto.PublicChangelogResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/public/changelog")
class PublicChangelogController(
	private val queryService: PublicChangelogQueryService,
) {
	@GetMapping("/{workspaceSlug}")
	fun list(@PathVariable workspaceSlug: String): ResponseEntity<PublicChangelogResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(queryService.list(workspaceSlug))

	@GetMapping("/{workspaceSlug}/{entrySlug}")
	fun getEntry(
		@PathVariable workspaceSlug: String,
		@PathVariable entrySlug: String,
	): ResponseEntity<PublicChangelogEntryDetailResponse> = ResponseEntity.ok()
		.cacheControl(CacheControl.noStore())
		.body(queryService.getEntry(workspaceSlug, entrySlug))
}
