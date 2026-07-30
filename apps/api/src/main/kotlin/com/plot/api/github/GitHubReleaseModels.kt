package com.plot.api.github

import java.time.Instant
import java.util.UUID

enum class GitHubWebhookDisposition { RECEIVED, OBSERVED, QUEUED, IGNORED, FAILED }

enum class GitHubReleaseDraftStatus {
	QUEUED, RESOLVING, GENERATING, READY, NO_ACTIVITY, NEEDS_RANGE, FAILED
}

data class GitHubWebhookDelivery(
	val id: UUID,
	val externalDeliveryId: String,
	val eventType: String,
	val eventAction: String?,
	val installationId: Long?,
	val repositoryId: Long?,
	val ref: String?,
	val beforeSha: String?,
	val afterSha: String?,
	val tagName: String?,
	val refCreated: Boolean?,
	val refDeleted: Boolean?,
	val forced: Boolean?,
	val payloadHash: String,
	val disposition: GitHubWebhookDisposition,
	val errorCode: String?,
	val receivedAt: Instant,
	val processedAt: Instant?,
)

data class GitHubReleaseDraftRequest(
	val id: UUID,
	val workspaceId: UUID,
	val sourceScopeId: UUID,
	val initialDeliveryId: UUID,
	val tagName: String,
	val baseSha: String?,
	val headSha: String?,
	val boundaryReason: String?,
	val status: GitHubReleaseDraftStatus,
	val attemptCount: Int,
	val transitionVersion: Long,
	val generationRunId: UUID?,
	val observationId: UUID?,
	val errorCode: String?,
	val generationAttempt: Int = 0,
	val observedHeadSha: String? = null,
)

data class GitHubReleaseRetryResult(
	val requestId: UUID,
	val generationRunId: UUID?,
	val generationAttempt: Int,
)

data class GitHubReleaseActivityRecord(
	val id: UUID,
	val sourceScopeId: UUID,
	val tagName: String,
	val status: GitHubReleaseDraftStatus,
	val baseSha: String?,
	val headSha: String?,
	val boundaryReason: String?,
	val generationRunId: UUID?,
	val contentPackId: UUID?,
	val errorCode: String?,
	val transitionVersion: Long,
	val createdAt: Instant,
	val updatedAt: Instant,
)

class GitHubReleaseRetryRejectedException : RuntimeException("GitHub release retry is no longer valid")
