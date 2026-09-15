package com.plot.api.routine

import com.plot.api.agent.AgentRunRecord
import com.plot.api.agent.AgentRunOrigin
import com.plot.api.agent.AgentRunStatus
import com.plot.api.persistence.SqlExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class RoutineAgentRunProjection(private val sqlExecutor: SqlExecutor) {
	fun commitSuccessfulInput(run: AgentRunRecord, now: Instant) {
		if (run.origin != AgentRunOrigin.ROUTINE || run.routineExecutionId == null || run.routineId == null) return
		sqlExecutor.update(
			"""
			update routines routine
			set activity_cursor_sequence = greatest(coalesce(routine.activity_cursor_sequence, 0), execution.activity_cursor_after),
			    updated_at = ?
			from routine_executions execution
			where routine.workspace_id = ? and routine.id = ?
			  and execution.workspace_id = routine.workspace_id and execution.id = ?
			  and execution.routine_id = routine.id
			  and execution.trigger_kind <> 'GITHUB'
			  and execution.activity_cursor_after is not null
			""".trimIndent(),
			Timestamp.from(now),
			run.workspaceId,
			run.routineId,
			run.routineExecutionId,
		)
	}

	fun projectTerminal(
		run: AgentRunRecord,
		status: AgentRunStatus,
		errorCode: String?,
		now: Instant,
	) {
		if (run.origin != AgentRunOrigin.ROUTINE) return
		val executionId = run.routineExecutionId ?: return
		val routineId = run.routineId ?: return
		if (status == AgentRunStatus.FAILED) {
			val code = requireNotNull(errorCode)
			sqlExecutor.update(
				"""
				update routine_executions
				set status = 'FAILED', error_code = ?, finished_at = coalesce(finished_at, ?),
				    transition_version = transition_version + 1, updated_at = ?
				where workspace_id = ? and id = ? and status = 'DISPATCHED'
				""".trimIndent(),
				code,
				Timestamp.from(now),
				Timestamp.from(now),
				run.workspaceId,
				executionId,
			)
			projectRoutineRow(run, executionId, routineId, "FAILED", code, null, now)
			return
		}
		val generationRunId = sqlExecutor.queryForObject(
			"""
			select generation_run_id
			from agent_steps
			where workspace_id = ? and agent_run_id = ? and generation_run_id is not null
			order by sequence desc, id desc
			limit 1
			""".trimIndent(),
			UUID::class.java,
			run.workspaceId,
			run.id,
		)
		val artifactStatus = sqlExecutor.queryForObject(
			"""
			select status
			from artifact_runs
			where workspace_id = ? and agent_run_id = ?
			order by created_at desc, id desc
			limit 1
			""".trimIndent(),
			String::class.java,
			run.workspaceId,
			run.id,
		) ?: "READY"
		projectRoutineRow(run, executionId, routineId, artifactStatus, null, generationRunId, now)
	}

	private fun projectRoutineRow(
		run: AgentRunRecord,
		executionId: UUID,
		routineId: UUID,
		status: String,
		errorCode: String?,
		generationRunId: UUID?,
		now: Instant,
	) {
		sqlExecutor.update(
			"""
			update routines
			set last_run_at = ?, last_execution_id = ?, last_generation_run_id = ?,
			    last_run_status = ?, last_error_code = ?,
			    active_execution_id = case when active_execution_id = ? then null else active_execution_id end,
			    claimed_by = case when active_execution_id = ? then null else claimed_by end,
			    claimed_at = case when active_execution_id = ? then null else claimed_at end,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ?
			  and (active_execution_id is null or active_execution_id = ?)
			  and (
			    last_execution_id = ?
			    or last_run_at is null
			    or last_run_at < ?
			    or (last_run_at = ? and (last_execution_id is null or last_execution_id < ?))
			  )
			""".trimIndent(),
			Timestamp.from(now),
			executionId,
			generationRunId,
			status,
			errorCode,
			executionId,
			executionId,
			executionId,
			Timestamp.from(now),
			run.workspaceId,
			routineId,
			executionId,
			executionId,
			Timestamp.from(now),
			Timestamp.from(now),
			executionId,
		)
		// A newer execution may already own the public Routine projection. The
		// terminal agent run remains authoritative; preserving the newer
		// projection is the intended result, not a claim failure.
	}

}
