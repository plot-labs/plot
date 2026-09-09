package com.plot.api.timeline

import com.plot.api.timeline.dto.ExecutionTimelineItem
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class TimelineController(
	private val timelineQueryService: TimelineQueryService,
) {
	@GetMapping("/sessions/{id}/timeline")
	fun listForSession(@PathVariable id: UUID): ResponseEntity<List<ExecutionTimelineItem>> =
		ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(timelineQueryService.listForSession(id))

	@GetMapping("/timeline/executions/{id}")
	fun getByExecutionId(@PathVariable id: UUID): ResponseEntity<ExecutionTimelineItem> =
		ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(timelineQueryService.getByExecutionId(id))
}
