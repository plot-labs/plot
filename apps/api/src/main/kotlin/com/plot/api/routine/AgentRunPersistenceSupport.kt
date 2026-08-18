package com.plot.api.routine

import com.plot.api.persistence.SqlRow
import java.time.Instant
import java.util.UUID

internal val executionMapper = { rs: SqlRow, _: Int -> rs.toRoutineExecution() }
internal val agentRunMapper = { rs: SqlRow, _: Int -> rs.toAgentRun() }
internal val agentRunSourceMapper = { rs: SqlRow, _: Int -> rs.toAgentRunSource() }
internal val agentRunInputMapper = { rs: SqlRow, _: Int -> rs.toAgentRunInput() }
internal val agentStepMapper = { rs: SqlRow, _: Int -> rs.toAgentStep() }


internal val selectExecutionSql = """
		select e.id, e.workspace_id, e.routine_id, e.created_by_user_id, e.trigger_source_scope_id,
		       e.trigger_kind, e.trigger_key, e.request_fingerprint, e.trigger_delivery_id,
		       e.scheduled_for, e.refresh_from, e.refresh_to, e.refresh_continuation::text,
		       e.refresh_completed_at, e.activity_cursor_before, e.activity_cursor_after,
		       e.status, e.attempt_count, e.transition_version, e.claimed_by, e.claimed_at,
		       e.next_attempt_at, e.error_code, e.started_at, e.finished_at, e.created_at, e.updated_at
		from routine_executions e
	""".trimIndent()

internal val selectAgentRunSql = """
		select a.id, a.workspace_id, a.routine_execution_id, a.work_session_id, a.routine_id, a.origin,
		       a.idempotency_key, a.request_fingerprint, a.created_by_user_id,
		       a.instruction_snapshot, a.prompt_version, a.tool_policy_version, a.budget_snapshot::text,
		       a.status, a.current_step, a.attempt_count, a.max_attempts,
		       a.model_call_count, a.tool_call_count, a.next_attempt_at,
		       a.failure_code, a.claimed_by, a.claimed_at, a.transition_version, a.started_at,
		       a.finished_at, a.created_at, a.updated_at
		from agent_runs a
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

internal fun SqlRow.toAgentRun() = AgentRunRecord(
		id = requireNotNull(getObject("id", UUID::class.java)),
		workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
		routineExecutionId = getObject("routine_execution_id", UUID::class.java),
		workSessionId = getObject("work_session_id", UUID::class.java),
		routineId = getObject("routine_id", UUID::class.java),
		origin = AgentRunOrigin.valueOf(requireNotNull(getString("origin"))),
		idempotencyKey = requireNotNull(getString("idempotency_key")),
		requestFingerprint = requireNotNull(getString("request_fingerprint")),
		createdByUserId = requireNotNull(getObject("created_by_user_id", UUID::class.java)),
		instructionSnapshot = requireNotNull(getString("instruction_snapshot")),
		promptVersion = requireNotNull(getString("prompt_version")),
		toolPolicyVersion = requireNotNull(getString("tool_policy_version")),
		budgetSnapshotJson = requireNotNull(getString("budget_snapshot")),
		status = AgentRunStatus.valueOf(requireNotNull(getString("status"))),
		currentStep = getInt("current_step"),
		attemptCount = getInt("attempt_count"),
		maxAttempts = getInt("max_attempts"),
		modelCallCount = getInt("model_call_count"),
		toolCallCount = getInt("tool_call_count"),
		nextAttemptAt = getTimestamp("next_attempt_at")?.toInstant(),
		failureCode = getString("failure_code"),
		claimedBy = getString("claimed_by"),
		claimedAt = getTimestamp("claimed_at")?.toInstant(),
		transitionVersion = getLong("transition_version"),
		startedAt = getTimestamp("started_at")?.toInstant(),
		finishedAt = getTimestamp("finished_at")?.toInstant(),
		createdAt = requireNotNull(getTimestamp("created_at")).toInstant(),
		updatedAt = requireNotNull(getTimestamp("updated_at")).toInstant(),
	)

internal fun SqlRow.toAgentRunSource() = AgentRunSourceRecord(
		id = requireNotNull(getObject("id", UUID::class.java)),
		workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
		agentRunId = requireNotNull(getObject("agent_run_id", UUID::class.java)),
		sourceScopeId = requireNotNull(getObject("source_scope_id", UUID::class.java)),
		role = AgentRunSourceRole.valueOf(requireNotNull(getString("source_role"))),
		orderIndex = getInt("order_index"),
		capturedStatus = requireNotNull(getString("captured_status")),
		capturedStatusChangedAt = requireNotNull(getTimestamp("captured_status_changed_at")).toInstant(),
		capturedAt = requireNotNull(getTimestamp("captured_at")).toInstant(),
	)

internal fun SqlRow.toAgentRunInput() = AgentRunInputRecord(
		id = requireNotNull(getObject("id", UUID::class.java)),
		workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
		agentRunId = requireNotNull(getObject("agent_run_id", UUID::class.java)),
		routineId = getObject("routine_id", UUID::class.java),
		sourceScopeId = requireNotNull(getObject("source_scope_id", UUID::class.java)),
		writingBlockId = requireNotNull(getObject("writing_block_id", UUID::class.java)),
		sourceProvider = requireNotNull(getString("source_provider")),
		sourceKind = requireNotNull(getString("source_kind")),
		sourceLabel = requireNotNull(getString("source_label")),
		inputKind = AgentRunInputKind.valueOf(requireNotNull(getString("input_kind"))),
		orderIndex = getInt("order_index"),
		activitySequence = getObject("activity_sequence", Long::class.javaObjectType),
		snapshotTitle = getString("snapshot_title"),
		snapshotBody = requireNotNull(getString("snapshot_body")),
		snapshotExcerpt = getString("snapshot_excerpt"),
		originalUrl = requireNotNull(getString("original_url")),
		sourceCreatedAt = getTimestamp("source_created_at")?.toInstant(),
		sourceUpdatedAt = getTimestamp("source_updated_at")?.toInstant(),
		contentHash = requireNotNull(getString("content_hash")),
		capturedAt = requireNotNull(getTimestamp("captured_at")).toInstant(),
	)

internal fun SqlRow.toAgentStep() = AgentStepRecord(
		id = requireNotNull(getObject("id", UUID::class.java)),
		workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java)),
		agentRunId = requireNotNull(getObject("agent_run_id", UUID::class.java)),
		sequence = getInt("sequence"),
		kind = AgentStepKind.valueOf(requireNotNull(getString("step_kind"))),
		status = AgentStepStatus.valueOf(requireNotNull(getString("status"))),
		idempotencyKey = requireNotNull(getString("idempotency_key")),
		toolName = getString("tool_name"),
		argumentsJson = requireNotNull(getString("arguments")),
		resultJson = getString("result"),
		adoptedInputId = getObject("adopted_input_id", UUID::class.java),
		artifactWorkflowRunId = getObject("generation_run_id", UUID::class.java),
		failureCode = getString("failure_code"),
		startedAt = getTimestamp("started_at")?.toInstant(),
		finishedAt = getTimestamp("finished_at")?.toInstant(),
		createdAt = requireNotNull(getTimestamp("created_at")).toInstant(),
	)
