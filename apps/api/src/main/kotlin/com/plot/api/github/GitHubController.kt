package com.plot.api.github

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/github")
class GitHubInstallationController(
	private val connectionService: GitHubConnectionService,
	private val importService: GitHubImportService,
) {
	@PostMapping("/installations/requests")
	fun createInstallationRequest(): ResponseEntity<GitHubInstallationRequestResponse> = ResponseEntity
		.ok()
		.cacheControl(CacheControl.noStore())
		.body(connectionService.createInstallationRequest())

	@PostMapping("/installations/callback")
	fun completeInstallation(@Valid @RequestBody request: GitHubCallbackRequest): ResponseEntity<GitHubCallbackResponse> = ResponseEntity
		.ok()
		.cacheControl(CacheControl.noStore())
		.body(connectionService.completeInstallation(request))

	@GetMapping("/installations/callback")
	fun completeInstallationFromRedirect(
		@RequestParam state: String,
		@RequestParam("installation_id") @Min(1) installationId: Long,
	): ResponseEntity<GitHubCallbackResponse> = ResponseEntity
		.ok()
		.cacheControl(CacheControl.noStore())
		.body(connectionService.completeInstallation(GitHubCallbackRequest(state, installationId)))

	@GetMapping("/connections")
	fun listConnections(): List<GitHubConnectionResponse> = connectionService.listConnections()

	@PutMapping("/repositories/{externalRepositoryId}")
	fun connectRepository(
		@PathVariable @Min(1) externalRepositoryId: Long,
		@Valid @RequestBody request: GitHubConnectRepositoryRequest,
	): GitHubRepositoryResponse = connectionService.connectRepository(externalRepositoryId, request)

	@DeleteMapping("/repositories/{id}")
	fun disconnectRepository(@PathVariable id: UUID): ResponseEntity<Void> {
		connectionService.disconnectRepository(id)
		return ResponseEntity.noContent().build()
	}

	@PostMapping("/repositories/{id}/imports")
	fun importRepository(
		@PathVariable id: UUID,
		@Valid @RequestBody request: GitHubImportRequest,
	): GitHubImportResponse = importService.start(id, request)

	@GetMapping("/imports/{id}")
	fun getImport(@PathVariable id: UUID): GitHubImportResponse = importService.get(id)
}
