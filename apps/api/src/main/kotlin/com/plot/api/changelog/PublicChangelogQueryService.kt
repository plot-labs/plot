package com.plot.api.changelog

import com.plot.api.changelog.dto.PublicChangelogEntryDetailResponse
import com.plot.api.changelog.dto.PublicChangelogEntrySummaryResponse
import com.plot.api.changelog.dto.PublicChangelogResponse
import com.plot.api.common.ApiException
import com.plot.api.workspace.WorkspaceRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class PublicChangelogQueryService(
	private val workspaceRepository: WorkspaceRepository,
	private val persistence: PublicChangelogPersistence,
) {
	fun list(workspaceSlug: String): PublicChangelogResponse {
		val workspace = workspaceRepository.findBySlug(workspaceSlug)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Changelog not found")
		val entries = persistence.listEntries(workspace.id)
		return PublicChangelogResponse(
			workspaceSlug = workspace.slug,
			workspaceName = workspace.name,
			logoUrl = workspace.logoUrl,
			entries = entries,
		)
	}

	fun getEntry(workspaceSlug: String, entrySlug: String): PublicChangelogEntryDetailResponse {
		val workspace = workspaceRepository.findBySlug(workspaceSlug)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Changelog not found")
		val entry = persistence.findEntry(workspace.id, entrySlug)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Changelog not found")
		return entry.copy(
			workspaceSlug = workspace.slug,
			workspaceName = workspace.name,
			logoUrl = workspace.logoUrl,
		)
	}
}
