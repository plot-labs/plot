package com.plot.api.workspace

import com.plot.api.workspace.dto.WorkspaceResponse
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/workspaces")
class WorkspaceController(
	private val workspaceService: WorkspaceService,
) {

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): WorkspaceResponse {
		return workspaceService.get(id)
	}
}
