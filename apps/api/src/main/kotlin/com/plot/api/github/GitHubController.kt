package com.plot.api.github

import com.plot.api.common.ApiException
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import java.time.Instant
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
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
	private val releaseActivityService: GitHubReleaseActivityService,
) {
	@PostMapping("/installations/requests")
	fun createInstallationRequest(): ResponseEntity<GitHubInstallationRequestResponse> = ResponseEntity
		.ok()
		.cacheControl(CacheControl.noStore())
		.body(connectionService.createInstallationRequest())

	@PostMapping("/installations/sync")
	fun syncExistingInstallation(): ResponseEntity<GitHubCallbackResponse> = ResponseEntity
		.ok()
		.cacheControl(CacheControl.noStore())
		.body(connectionService.syncExistingInstallation())

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

	@GetMapping("/connections/{connectionId}/repositories")
	fun listGrantedRepositories(@PathVariable connectionId: UUID): List<GitHubRepositoryResponse> =
		connectionService.listGrantedRepositories(connectionId)

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

	@GetMapping("/repositories/{sourceScopeId}/monitoring")
	fun getRepositoryMonitoring(
		@PathVariable sourceScopeId: UUID,
	): ResponseEntity<GitHubRepositoryMonitoringResponse> = ResponseEntity
		.ok()
		.cacheControl(CacheControl.noStore())
		.body(connectionService.getMonitoring(sourceScopeId))

	@PostMapping("/repositories/{sourceScopeId}/monitoring/retry")
	fun retryRepositoryMonitoring(
		@PathVariable sourceScopeId: UUID,
	): ResponseEntity<GitHubRepositoryMonitoringResponse> = ResponseEntity
		.accepted()
		.cacheControl(CacheControl.noStore())
		.body(connectionService.retryMonitoring(sourceScopeId))

	@PostMapping("/repositories/{sourceScopeId}/access-check")
	fun recheckRepositoryAccess(
		@PathVariable sourceScopeId: UUID,
		@RequestParam(defaultValue = "RETRY") trigger: String,
	): ResponseEntity<GitHubAccessCheckResponse> {
		val normalizedTrigger = runCatching { GitHubAccessCheckTrigger.valueOf(trigger.uppercase()) }
			.getOrElse {
				throw ApiException(
					HttpStatus.BAD_REQUEST,
					"BAD_REQUEST",
					"GitHub access check trigger is invalid",
				)
			}
		if (normalizedTrigger == GitHubAccessCheckTrigger.LIFECYCLE_EVENT) {
			throw ApiException(
				HttpStatus.BAD_REQUEST,
				"BAD_REQUEST",
				"Lifecycle events cannot be requested by a user",
			)
		}
		return ResponseEntity
			.accepted()
			.cacheControl(CacheControl.noStore())
			.body(connectionService.recheckAccess(sourceScopeId, normalizedTrigger))
	}

	@PostMapping("/repositories/{id}/imports")
	fun importRepository(
		@PathVariable id: UUID,
		@Valid @RequestBody request: GitHubImportRequest,
	): GitHubImportResponse = importService.start(id, request)

	@GetMapping("/imports/{id}")
	fun getImport(@PathVariable id: UUID): GitHubImportResponse = importService.get(id)

	@GetMapping("/repositories/{sourceScopeId}/release-activity")
	fun getReleaseActivity(
		@PathVariable sourceScopeId: UUID,
	): ResponseEntity<GitHubReleaseActivityResponse> {
		val activity = releaseActivityService.latest(sourceScopeId)
			?: return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(activity)
	}

	@PostMapping("/repositories/{sourceScopeId}/release-activity/{requestId}/retry")
	fun retryReleaseActivity(
		@PathVariable sourceScopeId: UUID,
		@PathVariable requestId: UUID,
	): ResponseEntity<GitHubReleaseActivityResponse> = ResponseEntity
		.ok()
		.cacheControl(CacheControl.noStore())
		.body(releaseActivityService.retry(sourceScopeId, requestId))
}

data class GitHubReleaseActivityResponse(
	val id: UUID,
	val sourceScopeId: UUID,
	val tagName: String,
	val status: GitHubReleaseDraftStatus,
	val baseSha: String?,
	val headSha: String?,
	val artifactId: UUID?,
	val errorCode: String?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

internal fun GitHubReleaseActivityRecord.toResponse() = GitHubReleaseActivityResponse(
	id = id,
	sourceScopeId = sourceScopeId,
	tagName = tagName,
	status = status,
	baseSha = baseSha,
	headSha = headSha,
	artifactId = artifactId,
	errorCode = errorCode,
	createdAt = createdAt,
	updatedAt = updatedAt,
)
