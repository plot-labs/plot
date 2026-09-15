package com.plot.api.routine

import com.plot.api.content.ContentType
import com.plot.api.persistence.SqlRow
import java.util.UUID

internal val executionMapper = { rs: SqlRow, _: Int -> rs.toRoutineExecution() }
internal val selectExecutionSql = """
		select e.id, e.workspace_id, e.routine_id, e.created_by_user_id, e.trigger_source_scope_id,
		       e.trigger_kind, e.trigger_key, e.request_fingerprint, e.trigger_delivery_id, e.release_request_id,
		       e.scheduled_for, e.refresh_from, e.refresh_to, e.refresh_continuation::text,
		       e.refresh_completed_at, e.activity_cursor_before, e.activity_cursor_after,
		       e.status, e.attempt_count, e.transition_version, e.claimed_by, e.claimed_at,
		       e.next_attempt_at, e.error_code, e.started_at, e.finished_at, e.created_at, e.updated_at
		from routine_executions e
	""".trimIndent()

internal fun SqlRow.toRoutineExecution() = RoutineExecutionRecord(
		id = requireNotNull(getObject("id", UUID::class.java)),
		workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
		routineId = requireNotNull(getObject("routine_id", UUID::class.java)),
		createdByUserId = requireNotNull(getObject("created_by_user_id", UUID::class.java)),
		triggerSourceScopeId = requireNotNull(getObject("trigger_source_scope_id", UUID::class.java)),
		triggerKind = RoutineExecutionTriggerKind.valueOf(requireNotNull(getString("trigger_kind"))),
		triggerKey = requireNotNull(getString("trigger_key")),
		requestFingerprint = requireNotNull(getString("request_fingerprint")),
		triggerDeliveryId = getObject("trigger_delivery_id", UUID::class.java),
		releaseRequestId = getObject("release_request_id", UUID::class.java),
		scheduledFor = getTimestamp("scheduled_for")?.toInstant(),
		refreshFrom = getTimestamp("refresh_from")?.toInstant(),
		refreshTo = getTimestamp("refresh_to")?.toInstant(),
		refreshContinuationJson = getString("refresh_continuation"),
		refreshCompletedAt = getTimestamp("refresh_completed_at")?.toInstant(),
		activityCursorBefore = getObject("activity_cursor_before", Long::class.javaObjectType),
		activityCursorAfter = getObject("activity_cursor_after", Long::class.javaObjectType),
		status = RoutineExecutionStatus.valueOf(requireNotNull(getString("status"))),
		attemptCount = getInt("attempt_count"),
		transitionVersion = getLong("transition_version"),
		claimedBy = getString("claimed_by"),
		claimedAt = getTimestamp("claimed_at")?.toInstant(),
		nextAttemptAt = getTimestamp("next_attempt_at")?.toInstant(),
		errorCode = getString("error_code"),
		startedAt = getTimestamp("started_at")?.toInstant(),
		finishedAt = getTimestamp("finished_at")?.toInstant(),
		createdAt = requireNotNull(getTimestamp("created_at")).toInstant(),
		updatedAt = requireNotNull(getTimestamp("updated_at")).toInstant(),
	)
