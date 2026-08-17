package com.plot.api.github

import java.time.Duration
import java.time.Instant
import java.util.UUID

interface GitHubWebhookDeliveryStore {
	fun insertDelivery(delivery: GitHubWebhookDelivery): GitHubWebhookDelivery
	fun findDelivery(externalDeliveryId: String): GitHubWebhookDelivery?
	fun findDelivery(id: UUID): GitHubWebhookDelivery?
	fun markDelivery(id: UUID, disposition: GitHubWebhookDisposition, errorCode: String? = null)
}

interface GitHubReleaseRequestStore {
	fun findLatest(sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseDraftRequest?
	fun releaseScopeExists(sourceScopeId: UUID, workspaceId: UUID): Boolean
	fun findLatestActivity(sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseActivityRecord?
	fun findActivity(requestId: UUID, sourceScopeId: UUID, workspaceId: UUID): GitHubReleaseActivityRecord?
	fun findPreviousBoundaries(
		workspaceId: UUID,
		sourceScopeId: UUID,
		excludingRequestId: UUID,
	): List<GitHubReleaseDraftRequest>
	fun findBoundEvidence(requestId: UUID): GitHubReleaseEvidence?
	fun findGenerating(limit: Int): List<GitHubReleaseDraftRequest>
	fun enqueueRelease(
		workspaceId: UUID,
		sourceScopeId: UUID,
		deliveryId: UUID,
		tagName: String,
		observedHeadSha: String?,
	): GitHubReleaseDraftRequest
	fun saveResolvedRange(
		requestId: UUID,
		transitionVersion: Long,
		baseSha: String,
		headSha: String,
		boundaryReason: String,
	)
	fun saveHeadAndFinishNeedsRange(requestId: UUID, transitionVersion: Long, headSha: String)
	fun linkAgentRun(requestId: UUID, transitionVersion: Long, observationId: UUID, agentRunId: UUID)
	fun linkAgentArtifact(requestId: UUID, transitionVersion: Long, agentRunId: UUID, artifactWorkflowRunId: UUID)
	fun bindEvidence(requestId: UUID, transitionVersion: Long, evidence: GitHubReleaseEvidence)
}

interface GitHubReleaseLeaseStore {
	fun claimNext(workerId: String, now: Instant, leaseTimeout: Duration): GitHubReleaseDraftRequest?
	fun renewClaim(requestId: UUID, transitionVersion: Long, workerId: String, now: Instant): Boolean
	fun finish(
		requestId: UUID,
		transitionVersion: Long,
		status: GitHubReleaseDraftStatus,
		errorCode: String? = null,
	)
	fun retry(requestId: UUID, workspaceId: UUID, transitionVersion: Long): GitHubReleaseRetryResult
	fun scheduleRetry(requestId: UUID, transitionVersion: Long, nextAttemptAt: Instant, errorCode: String)
	fun fenceSourceScope(
		workspaceId: UUID,
		sourceScopeId: UUID,
		now: Instant,
		errorCode: String = "SOURCE_ACCESS_LOST",
	): Int
	fun recoverStaleClaims(now: Instant, leaseTimeout: Duration): Int
	fun recordReconcileDiagnostic(requestId: UUID, transitionVersion: Long, errorCode: String)
}
