package com.plot.api.routine

import java.time.Instant
import java.util.UUID

enum class RoutineCadence {
	DAILY, WEEKLY, ON_GITHUB_CHANGE, ON_GITHUB_RELEASE, ON_GIT_TAG;

	fun nextAfter(now: Instant): Instant = now.plusSeconds(
		when (this) {
			DAILY -> 24 * 60 * 60L
			WEEKLY -> 7 * 24 * 60 * 60L
			ON_GITHUB_CHANGE, ON_GITHUB_RELEASE, ON_GIT_TAG -> 0L
		},
	)
}

data class RoutineRecord(
	val id: UUID,
	val workspaceId: UUID,
	val createdByUserId: UUID,
	val sourceScopeId: UUID,
	val sourceLabel: String,
	val name: String,
	val instruction: String,
	val cadence: RoutineCadence,
	val enabled: Boolean,
	val lastRunAt: Instant?,
	val nextRunAt: Instant,
	val lastGenerationRunId: UUID?,
	val lastRunStatus: String?,
	val lastErrorCode: String?,
	val claimedBy: String?,
	val claimedAt: Instant?,
	val transitionVersion: Long,
	val createdAt: Instant,
	val updatedAt: Instant,
)
