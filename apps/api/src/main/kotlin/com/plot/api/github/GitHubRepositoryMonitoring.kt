package com.plot.api.github

import java.time.Instant
import java.util.UUID

enum class GitHubRepositoryMonitoringStatus {
	ACTIVE,
	DISABLED,
}

enum class GitHubRepositoryAnalysisStatus {
	QUEUED,
	ANALYZING,
	COMPLETED,
	FAILED,
}

data class GitHubRepositoryMonitoringRecord(
	val id: UUID,
	val workspaceId: UUID,
	val sourceScopeId: UUID,
	val monitoringStatus: GitHubRepositoryMonitoringStatus,
	val analysisStatus: GitHubRepositoryAnalysisStatus,
	val releaseConvention: GitHubReleaseConvention?,
	val tagPrefix: String?,
	val sampleSource: GitHubReleaseSampleSource?,
	val sampleSize: Int,
	val sampleTruncated: Boolean,
	val attemptCount: Int,
	val transitionVersion: Long,
	val claimedBy: String?,
	val claimedAt: Instant?,
	val nextAttemptAt: Instant?,
	val lastErrorCode: String?,
	val analyzedAt: Instant?,
	val createdAt: Instant,
	val updatedAt: Instant,
)

data class GitHubRepositoryMonitoringWorkItem(
	val monitoring: GitHubRepositoryMonitoringRecord,
	val connectionId: UUID,
	val installationId: Long,
	val repositoryId: Long,
	val owner: String,
	val repository: String,
)

data class GitHubRepositoryMonitoringResponse(
	val status: GitHubRepositoryMonitoringStatus,
	val analysisStatus: GitHubRepositoryAnalysisStatus,
	val releaseConvention: GitHubReleaseConvention?,
	val tagPrefix: String?,
	val sampleSource: GitHubReleaseSampleSource?,
	val sampleSize: Int,
	val sampleTruncated: Boolean,
	val attemptCount: Int,
	val lastErrorCode: String?,
	val analyzedAt: Instant?,
)

fun GitHubRepositoryMonitoringRecord.toResponse() = GitHubRepositoryMonitoringResponse(
	status = monitoringStatus,
	analysisStatus = analysisStatus,
	releaseConvention = releaseConvention,
	tagPrefix = tagPrefix,
	sampleSource = sampleSource,
	sampleSize = sampleSize,
	sampleTruncated = sampleTruncated,
	attemptCount = attemptCount,
	lastErrorCode = lastErrorCode,
	analyzedAt = analyzedAt,
)
