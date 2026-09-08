package com.plot.api.contentprofile

import com.plot.api.auth.RequestActorResolver
import com.plot.api.common.ApiException
import com.plot.api.content.ContentProfileRevision
import com.plot.api.contentprofile.dto.ContentProfileResponse
import com.plot.api.contentprofile.dto.UpdateContentProfileRequest
import com.plot.api.dev.DevContext
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class ContentProfileService(
	private val persistence: ContentProfilePersistence,
	private val devContext: DevContext,
	private val actorResolver: RequestActorResolver? = null,
) {
	fun get(): ContentProfileResponse {
		val revision = persistence.findCurrentRevision(devContext.devWorkspaceId)
		return revision.toResponse()
	}

	fun update(request: UpdateContentProfileRequest): ContentProfileResponse {
		requireOwner()
		val current = persistence.findCurrentRevision(devContext.devWorkspaceId)
		val revision = persistence.appendRevision(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			productSummary = request.productSummary?.trim() ?: current?.productSummary.orEmpty(),
			primaryAudience = request.primaryAudience?.trim() ?: current?.primaryAudience.orEmpty(),
			customerTerms = request.customerTerms?.trim() ?: current?.customerTerms.orEmpty(),
			tone = request.tone?.trim() ?: current?.tone.orEmpty(),
			defaultLocale = (request.defaultLocale?.trim()?.ifBlank { null } ?: current?.defaultLocale ?: "en"),
			bannedPhrases = (request.bannedPhrases ?: current?.bannedPhrases.orEmpty())
				.map { it.trim() }
				.filter { it.isNotEmpty() }
				.distinct(),
		)
		return revision.toResponse()
	}

	fun requireRevisionInWorkspace(workspaceId: UUID, revisionId: UUID): ContentProfileRevision =
		persistence.findRevision(workspaceId, revisionId)
			?: throw ApiException(HttpStatus.NOT_FOUND, "CONTENT_PROFILE_REVISION_NOT_FOUND", "Content profile revision was not found")

	fun currentRevisionId(workspaceId: UUID): UUID? =
		persistence.findCurrentRevision(workspaceId)?.id

	private fun requireOwner() {
		val actor = actorResolver?.current()
		if (actor != null && actorResolver.requireWorkspace().role != "OWNER") {
			throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only workspace owners can update content profile")
		}
	}

	private fun ContentProfileRevision?.toResponse(): ContentProfileResponse = ContentProfileResponse(
		revisionId = this?.id,
		revisionNumber = this?.revisionNumber,
		productSummary = this?.productSummary.orEmpty(),
		primaryAudience = this?.primaryAudience.orEmpty(),
		customerTerms = this?.customerTerms.orEmpty(),
		tone = this?.tone.orEmpty(),
		defaultLocale = this?.defaultLocale ?: "en",
		bannedPhrases = this?.bannedPhrases.orEmpty(),
		updatedAt = this?.createdAt,
	)
}
