package com.plot.api.agent

import com.plot.api.content.ContentType
import com.plot.api.persistence.SqlRow
import java.util.UUID

internal val agentRunMapper = { rs: SqlRow, _: Int -> rs.toAgentRun() }
internal val agentRunSourceMapper = { rs: SqlRow, _: Int -> rs.toAgentRunSource() }
internal val agentRunInputMapper = { rs: SqlRow, _: Int -> rs.toAgentRunInput() }
internal val agentStepMapper = { rs: SqlRow, _: Int -> rs.toAgentStep() }


internal val selectAgentRunSql = """
		select a.id, a.workspace_id, a.routine_execution_id, a.work_session_id, a.routine_id, a.origin,
		       a.idempotency_key, a.request_fingerprint, a.created_by_user_id,
		       a.instruction_snapshot, a.skills_snapshot::text, a.prompt_version, a.tool_policy_version, a.budget_snapshot::text,
		       a.content_type, a.content_profile_revision_id, a.content_brief_snapshot::text,
		       a.status, a.current_step, a.attempt_count, a.max_attempts,
		       a.model_call_count, a.tool_call_count, a.next_attempt_at,
		       a.failure_code, a.claimed_by, a.claimed_at, a.transition_version, a.started_at,
		       a.finished_at, a.created_at, a.updated_at, a.source_snapshot_id
		from agent_runs a
	""".trimIndent()

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
		skillsSnapshotJson = requireNotNull(getString("skills_snapshot")),
		promptVersion = requireNotNull(getString("prompt_version")),
		toolPolicyVersion = requireNotNull(getString("tool_policy_version")),
		budgetSnapshotJson = requireNotNull(getString("budget_snapshot")),
		contentType = ContentType.parse(getString("content_type")),
		contentProfileRevisionId = getObject("content_profile_revision_id", UUID::class.java),
		contentBriefSnapshotJson = getString("content_brief_snapshot"),
		sourceSnapshotId = getObject("source_snapshot_id", UUID::class.java),
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
		displayName = getString("source_display_name"),
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
