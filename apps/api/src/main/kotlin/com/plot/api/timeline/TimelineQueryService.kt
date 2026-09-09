package com.plot.api.timeline

import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.SqlRow
import com.plot.api.timeline.dto.ExecutionTimelineItem
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TimelineQueryService @org.springframework.beans.factory.annotation.Autowired constructor(
	private val devContext: DevContext,
	private val sqlExecutor: JooqSqlExecutor,
	private val workspaceAccessService: WorkspaceAccessService,
	private val clock: Clock = Clock.systemUTC(),
) {
	@Transactional(readOnly = true)
	fun listForSession(sessionId: UUID, workspaceId: UUID = devContext.devWorkspaceId): List<ExecutionTimelineItem> {
		workspaceAccessService.requireActiveWorkspace(workspaceId)
		val sessionExists = sqlExecutor.query(
			"select id from work_sessions where workspace_id = ? and id = ?",
			{ rs, _ -> requireNotNull(rs.getObject("id", UUID::class.java)) },
			workspaceId,
			sessionId,
		).isNotEmpty()
		if (!sessionExists) {
			throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Work session not found")
		}

		return sqlExecutor.query(
			"""
			select
			  agent.id as agent_run_id,
			  agent.workspace_id,
			  agent.origin as agent_origin,
			  agent.status as agent_status,
			  agent.attempt_count as agent_attempt_count,
			  agent.max_attempts as agent_max_attempts,
			  agent.next_attempt_at as agent_next_attempt_at,
			  agent.failure_code as agent_failure_code,
			  agent.created_at as agent_created_at,
			  agent.updated_at as agent_updated_at,
			  agent.finished_at as agent_finished_at,
			  agent.work_session_id,
			  agent.routine_execution_id,
			  workflow.id as generation_run_id,
			  workflow.status as generation_status,
			  workflow.next_attempt_at as generation_next_attempt_at,
			  pack.id as artifact_id,
			  release_req.id as release_draft_id,
			  release_req.initial_delivery_id as release_delivery_id,
			  routine.id as routine_id
			from agent_runs agent
			left join lateral (
			  select step.generation_run_id
			  from agent_steps step
			  where step.workspace_id = agent.workspace_id
			    and step.agent_run_id = agent.id
			    and step.generation_run_id is not null
			  order by step.sequence desc, step.id desc
			  limit 1
			) handoff on true
			left join generation_runs workflow
			  on workflow.workspace_id = agent.workspace_id
			 and workflow.id = handoff.generation_run_id
			left join content_packs pack
			  on pack.workspace_id = agent.workspace_id
			 and pack.generation_run_id = workflow.id
			left join routine_executions routine_exec
			  on routine_exec.workspace_id = agent.workspace_id
			 and routine_exec.id = agent.routine_execution_id
			left join routines routine
			  on routine.workspace_id = agent.workspace_id
			 and routine.id = routine_exec.routine_id
			left join github_release_draft_requests release_req
			  on release_req.workspace_id = agent.workspace_id
			 and release_req.agent_run_id = agent.id
			where agent.workspace_id = ? and agent.work_session_id = ?
			order by agent.created_at desc, agent.id desc
			""".trimIndent(),
			{ row, _ -> row.toTimelineItem() },
			workspaceId,
			sessionId,
		)
	}

	@Transactional(readOnly = true)
	fun getByExecutionId(executionId: UUID, workspaceId: UUID = devContext.devWorkspaceId): ExecutionTimelineItem {
		workspaceAccessService.requireActiveWorkspace(workspaceId)
		val byAgent = sqlExecutor.query(
			"""
			select
			  agent.id as agent_run_id,
			  agent.workspace_id,
			  agent.origin as agent_origin,
			  agent.status as agent_status,
			  agent.attempt_count as agent_attempt_count,
			  agent.max_attempts as agent_max_attempts,
			  agent.next_attempt_at as agent_next_attempt_at,
			  agent.failure_code as agent_failure_code,
			  agent.created_at as agent_created_at,
			  agent.updated_at as agent_updated_at,
			  agent.finished_at as agent_finished_at,
			  agent.work_session_id,
			  agent.routine_execution_id,
			  workflow.id as generation_run_id,
			  workflow.status as generation_status,
			  workflow.next_attempt_at as generation_next_attempt_at,
			  pack.id as artifact_id,
			  release_req.id as release_draft_id,
			  release_req.initial_delivery_id as release_delivery_id,
			  routine.id as routine_id
			from agent_runs agent
			left join lateral (
			  select step.generation_run_id
			  from agent_steps step
			  where step.workspace_id = agent.workspace_id
			    and step.agent_run_id = agent.id
			    and step.generation_run_id is not null
			  order by step.sequence desc, step.id desc
			  limit 1
			) handoff on true
			left join generation_runs workflow
			  on workflow.workspace_id = agent.workspace_id
			 and workflow.id = handoff.generation_run_id
			left join content_packs pack
			  on pack.workspace_id = agent.workspace_id
			 and pack.generation_run_id = workflow.id
			left join routine_executions routine_exec
			  on routine_exec.workspace_id = agent.workspace_id
			 and routine_exec.id = agent.routine_execution_id
			left join routines routine
			  on routine.workspace_id = agent.workspace_id
			 and routine.id = routine_exec.routine_id
			left join github_release_draft_requests release_req
			  on release_req.workspace_id = agent.workspace_id
			 and release_req.agent_run_id = agent.id
			where agent.workspace_id = ?
			  and (agent.id = ? or handoff.generation_run_id = ? or agent.routine_execution_id = ? or pack.id = ?)
			order by agent.created_at desc, agent.id desc
			limit 1
			""".trimIndent(),
			{ row, _ -> row.toTimelineItem() },
			workspaceId,
			executionId,
			executionId,
			executionId,
			executionId,
		).firstOrNull()

		if (byAgent != null) return byAgent

		val byRelease = sqlExecutor.query(
			"""
			select
			  req.id as release_draft_id,
			  req.workspace_id,
			  'RELEASE' as agent_origin,
			  req.status as release_status,
			  req.attempt_count as release_attempt_count,
			  req.next_attempt_at as release_next_attempt_at,
			  req.error_code as release_error_code,
			  req.created_at as release_created_at,
			  req.updated_at as release_updated_at,
			  req.finished_at as release_finished_at,
			  req.initial_delivery_id,
			  req.agent_run_id,
			  req.routine_id
			from github_release_draft_requests req
			where req.workspace_id = ?
			  and (req.id = ? or req.agent_run_id = ? or req.generation_run_id = ?)
			limit 1
			""".trimIndent(),
			{ row, _ -> row.toReleaseTimelineItem() },
			workspaceId,
			executionId,
			executionId,
			executionId,
		).firstOrNull()

		if (byRelease != null) return byRelease

		val byStandaloneWorkflow = sqlExecutor.query(
			"""
			select
			  workflow.id as generation_run_id,
			  workflow.workspace_id,
			  'ARTIFACT' as origin,
			  workflow.status as generation_status,
			  workflow.semantic_rewrite_attempt as generation_attempt_count,
			  workflow.next_attempt_at as generation_next_attempt_at,
			  workflow.error_code as generation_error_code,
			  workflow.created_at,
			  workflow.updated_at,
			  workflow.finished_at,
			  workflow.agent_run_id,
			  agent.routine_execution_id,
			  pack.id as artifact_id
			from generation_runs workflow
			left join agent_runs agent
			  on agent.workspace_id = workflow.workspace_id
			 and agent.id = workflow.agent_run_id
			left join content_packs pack
			  on pack.workspace_id = workflow.workspace_id
			 and pack.generation_run_id = workflow.id
			where workflow.workspace_id = ? and (workflow.id = ? or pack.id = ?)
			limit 1
			""".trimIndent(),
			{ row, _ -> row.toArtifactTimelineItem() },
			workspaceId,
			executionId,
			executionId,
		).firstOrNull()

		if (byStandaloneWorkflow != null) return byStandaloneWorkflow

		throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Execution timeline not found")
	}

	private fun SqlRow.toTimelineItem(): ExecutionTimelineItem {
		val agentRunId = requireNotNull(getObject("agent_run_id", UUID::class.java))
		val workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java))
		val origin = getString("agent_origin") ?: "CHAT"
		val rawStatus = getString("agent_status") ?: "QUEUED"
		val generationStatus = getString("generation_status")
		val failureCode = sanitizeErrorCode(getString("agent_failure_code"))
		val nextAttemptAt = getTimestamp("agent_next_attempt_at")?.toInstant()
			?: getTimestamp("generation_next_attempt_at")?.toInstant()
		val createdAt = requireNotNull(getTimestamp("agent_created_at")?.toInstant())
		val updatedAt = requireNotNull(getTimestamp("agent_updated_at")?.toInstant())
		val finishedAt = getTimestamp("agent_finished_at")?.toInstant()

		val stage = when {
			generationStatus != null -> "ARTIFACT"
			rawStatus in setOf("RUNNING", "WAITING_FOR_ARTIFACT") -> "AGENT"
			rawStatus == "QUEUED" -> "ADMISSION"
			else -> "EXECUTION"
		}

		val effectiveStatus = when {
			failureCode in CONNECTION_ERROR_CODES -> "NEEDS_CONNECTION"
			rawStatus == "FAILED" || generationStatus == "FAILED" -> "FAILED"
			rawStatus == "NO_ACTIVITY" -> "NO_ACTIVITY"
			rawStatus == "SUCCEEDED" -> "READY"
			nextAttemptAt != null && nextAttemptAt.isAfter(clock.instant()) -> "RETRY_SCHEDULED"
			rawStatus in setOf("RUNNING", "WAITING_FOR_ARTIFACT") || generationStatus in setOf("QUEUED", "WRITING", "REVIEWING", "REWRITING") -> "RUNNING"
			rawStatus == "QUEUED" -> "QUEUED"
			else -> rawStatus
		}

		val statusLabel = when (effectiveStatus) {
			"QUEUED" -> "Waiting to start"
			"RUNNING" -> "Running"
			"RETRY_SCHEDULED" -> "Retry scheduled"
			"NEEDS_CONNECTION" -> "Needs connection"
			"FAILED" -> "Failed"
			"NO_ACTIVITY" -> "No activity"
			"READY" -> "Ready for review"
			else -> "Waiting to start"
		}

		val recoveryAction = when (effectiveStatus) {
			"NEEDS_CONNECTION" -> "Reconnect repository access"
			"RETRY_SCHEDULED" -> "Automatic retry scheduled"
			"FAILED" -> "Review safe error code and retry"
			else -> null
		}

		return ExecutionTimelineItem(
			id = agentRunId,
			workspaceId = workspaceId,
			origin = origin,
			stage = stage,
			status = effectiveStatus,
			statusLabel = statusLabel,
			deliveryId = getObject("release_delivery_id", UUID::class.java),
			releaseRequestId = getObject("release_draft_id", UUID::class.java),
			routineId = getObject("routine_id", UUID::class.java),
			routineExecutionId = getObject("routine_execution_id", UUID::class.java),
			agentRunId = agentRunId,
			artifactWorkflowRunId = getObject("generation_run_id", UUID::class.java),
			artifactId = getObject("artifact_id", UUID::class.java),
			workSessionId = getObject("work_session_id", UUID::class.java),
			attemptCount = getInt("agent_attempt_count"),
			maxAttempts = getObject("agent_max_attempts", Int::class.javaObjectType),
			nextAttemptAt = nextAttemptAt,
			safeErrorCode = failureCode,
			recoveryAction = recoveryAction,
			createdAt = createdAt,
			updatedAt = updatedAt,
			finishedAt = finishedAt,
		)
	}

	private fun SqlRow.toReleaseTimelineItem(): ExecutionTimelineItem {
		val requestId = requireNotNull(getObject("release_draft_id", UUID::class.java))
		val workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java))
		val rawStatus = getString("release_status") ?: "QUEUED"
		val failureCode = sanitizeErrorCode(getString("release_error_code"))
		val nextAttemptAt = getTimestamp("release_next_attempt_at")?.toInstant()
		val createdAt = requireNotNull(getTimestamp("release_created_at")?.toInstant())
		val updatedAt = requireNotNull(getTimestamp("release_updated_at")?.toInstant())
		val finishedAt = getTimestamp("release_finished_at")?.toInstant()

		val stage = "RELEASE"
		val effectiveStatus = when {
			failureCode in CONNECTION_ERROR_CODES -> "NEEDS_CONNECTION"
			rawStatus == "FAILED" -> "FAILED"
			rawStatus == "NO_ACTIVITY" -> "NO_ACTIVITY"
			rawStatus in setOf("READY", "NEEDS_RANGE") -> "READY"
			nextAttemptAt != null && nextAttemptAt.isAfter(clock.instant()) -> "RETRY_SCHEDULED"
			rawStatus in setOf("RESOLVING", "GENERATING") -> "RUNNING"
			rawStatus == "QUEUED" -> "QUEUED"
			else -> rawStatus
		}

		val statusLabel = when (effectiveStatus) {
			"QUEUED" -> "Waiting to start"
			"RUNNING" -> "Running"
			"RETRY_SCHEDULED" -> "Retry scheduled"
			"NEEDS_CONNECTION" -> "Needs connection"
			"FAILED" -> "Failed"
			"NO_ACTIVITY" -> "No activity"
			"READY" -> "Ready for review"
			else -> "Waiting to start"
		}

		val recoveryAction = when (effectiveStatus) {
			"NEEDS_CONNECTION" -> "Reconnect repository access"
			"RETRY_SCHEDULED" -> "Automatic retry scheduled"
			"FAILED" -> "Review safe error code and retry"
			else -> null
		}

		return ExecutionTimelineItem(
			id = requestId,
			workspaceId = workspaceId,
			origin = "RELEASE",
			stage = stage,
			status = effectiveStatus,
			statusLabel = statusLabel,
			deliveryId = getObject("initial_delivery_id", UUID::class.java),
			releaseRequestId = requestId,
			routineId = getObject("routine_id", UUID::class.java),
			routineExecutionId = null,
			agentRunId = getObject("agent_run_id", UUID::class.java),
			artifactWorkflowRunId = null,
			artifactId = null,
			workSessionId = null,
			attemptCount = getInt("release_attempt_count"),
			maxAttempts = null,
			nextAttemptAt = nextAttemptAt,
			safeErrorCode = failureCode,
			recoveryAction = recoveryAction,
			createdAt = createdAt,
			updatedAt = updatedAt,
			finishedAt = finishedAt,
		)
	}

	private fun SqlRow.toArtifactTimelineItem(): ExecutionTimelineItem {
		val workflowRunId = requireNotNull(getObject("generation_run_id", UUID::class.java))
		val workspaceId = requireNotNull(getObject("workspace_id", UUID::class.java))
		val origin = getString("origin") ?: "ARTIFACT"
		val rawStatus = getString("generation_status") ?: "QUEUED"
		val failureCode = sanitizeErrorCode(getString("generation_error_code"))
		val nextAttemptAt = getTimestamp("generation_next_attempt_at")?.toInstant()
		val createdAt = requireNotNull(getTimestamp("created_at")?.toInstant())
		val updatedAt = requireNotNull(getTimestamp("updated_at")?.toInstant())
		val finishedAt = getTimestamp("finished_at")?.toInstant()

		val stage = "ARTIFACT"
		val effectiveStatus = when {
			failureCode in CONNECTION_ERROR_CODES -> "NEEDS_CONNECTION"
			rawStatus == "FAILED" -> "FAILED"
			rawStatus in setOf("READY", "NEEDS_REVIEW") -> "READY"
			nextAttemptAt != null && nextAttemptAt.isAfter(clock.instant()) -> "RETRY_SCHEDULED"
			rawStatus in setOf("WRITING", "REVIEWING", "REWRITING") -> "RUNNING"
			rawStatus == "QUEUED" -> "QUEUED"
			else -> rawStatus
		}

		val statusLabel = when (effectiveStatus) {
			"QUEUED" -> "Waiting to start"
			"RUNNING" -> "Running"
			"RETRY_SCHEDULED" -> "Retry scheduled"
			"NEEDS_CONNECTION" -> "Needs connection"
			"FAILED" -> "Failed"
			"NO_ACTIVITY" -> "No activity"
			"READY" -> "Ready for review"
			else -> "Waiting to start"
		}

		val recoveryAction = when (effectiveStatus) {
			"NEEDS_CONNECTION" -> "Reconnect repository access"
			"RETRY_SCHEDULED" -> "Automatic retry scheduled"
			"FAILED" -> "Review safe error code and retry"
			else -> null
		}

		return ExecutionTimelineItem(
			id = workflowRunId,
			workspaceId = workspaceId,
			origin = origin,
			stage = stage,
			status = effectiveStatus,
			statusLabel = statusLabel,
			deliveryId = null,
			releaseRequestId = null,
			routineId = null,
			routineExecutionId = getObject("routine_execution_id", UUID::class.java),
			agentRunId = getObject("agent_run_id", UUID::class.java),
			artifactWorkflowRunId = workflowRunId,
			artifactId = getObject("artifact_id", UUID::class.java),
			workSessionId = null,
			attemptCount = getInt("generation_attempt_count"),
			maxAttempts = null,
			nextAttemptAt = nextAttemptAt,
			safeErrorCode = failureCode,
			recoveryAction = recoveryAction,
			createdAt = createdAt,
			updatedAt = updatedAt,
			finishedAt = finishedAt,
		)
	}

	private fun sanitizeErrorCode(raw: String?): String? {
		if (raw.isNullOrBlank()) return null
		val trimmed = raw.trim()
		if (trimmed.length in 1..100 && trimmed.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
			return trimmed
		}
		return "UNKNOWN_ERROR"
	}

	private companion object {
		val CONNECTION_ERROR_CODES = setOf(
			"SOURCE_NOT_READY",
			"GITHUB_ACCESS_DENIED",
			"GITHUB_NOT_FOUND",
			"INVALID_SESSION",
			"REPOSITORY_INACTIVE",
		)
	}
}
