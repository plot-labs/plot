package com.plot.api.workspace

import com.plot.api.entitlement.ReadOnlyAllowed
import com.plot.api.workspace.dto.CreateWorkspaceRequest
import com.plot.api.workspace.dto.UpdateWorkspaceRequest
import com.plot.api.workspace.dto.WorkspaceResponse
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/workspaces")
class WorkspaceController(
	private val workspaceService: WorkspaceService,
) {

	@PostMapping
	@ReadOnlyAllowed
	fun create(@Valid @RequestBody request: CreateWorkspaceRequest): WorkspaceResponse = workspaceService.create(request)

	@GetMapping("/{id}")
	fun get(@PathVariable id: UUID): WorkspaceResponse {
		return workspaceService.get(id)
	}

	@PatchMapping("/{id}")
	fun update(
		@PathVariable id: UUID,
		@Valid @RequestBody request: UpdateWorkspaceRequest,
	): WorkspaceResponse = workspaceService.update(id, request)
}
