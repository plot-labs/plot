package com.plot.api.artifact.workflow

import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.SqlRow
import com.plot.api.artifact.workflow.dto.ArtifactWorkflowModelTimingResponse
import com.plot.api.artifact.workflow.dto.ArtifactWorkflowRunTimingResponse
import com.plot.api.artifact.workflow.dto.ArtifactWorkflowStepTimingResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import tools.jackson.databind.ObjectMapper

class ArtifactWorkflowQueryPersistence(
	private val sqlExecutor: JooqSqlExecutor,
	private val objectMapper: ObjectMapper,
) {
	fun loadState(workspaceId: UUID, runId: UUID): ArtifactWorkflowState {
		val payload = sqlExecutor.query(
			"select payload::text from generation_artifacts where workspace_id = ? and generation_run_id = ? order by sequence_no desc limit 1",
			{ rs, _ -> rs.getString(1) }, workspaceId, runId,
		).firstOrNull() ?: throw ArtifactWorkflowRunNotFoundException(runId)
		return objectMapper.readValue(payload, ArtifactWorkflowState::class.java)
	}

	fun loadTiming(workspaceId: UUID, runId: UUID): ArtifactWorkflowRunTimingResponse {
		val run = sqlExecutor.query(
			"""
			select gr.created_at, gr.started_at, gr.finished_at, gr.model_name,
			       coalesce(sum(coalesce(mi.total_token_count, 0)), 0),
			       coalesce(sum(coalesce(mi.latency_ms, 0)), 0)
			from generation_runs gr
			left join model_invocations mi on mi.workspace_id = gr.workspace_id and mi.generation_run_id = gr.id
			where gr.workspace_id = ? and gr.id = ?
			group by gr.created_at, gr.started_at, gr.finished_at, gr.model_name
			""".trimIndent(),
			{ rs, _ ->
					RunTimingRow(
						requireNotNull(rs.getTimestamp("created_at")).toInstant(),
					rs.getTimestamp("started_at")?.toInstant(),
					rs.getTimestamp("finished_at")?.toInstant(),
						requireNotNull(rs.getString("model_name")),
					rs.getLong(5),
					rs.getLong(6),
				)
			},
			workspaceId, runId,
		).firstOrNull() ?: throw ArtifactWorkflowRunNotFoundException(runId)

		val steps = sqlExecutor.query(
			"""
			select step_kind, sequence_no, status, started_at, finished_at, failure_code
			from generation_workflow_steps
			where workspace_id = ? and generation_run_id = ?
			order by sequence_no
			""".trimIndent(),
			{ rs, _ ->
					val startedAt = requireNotNull(rs.getTimestamp("started_at")).toInstant()
				val finishedAt = rs.getTimestamp("finished_at")?.toInstant()
				ArtifactWorkflowStepTimingResponse(
						kind = requireNotNull(rs.getString("step_kind")),
					sequence = rs.getInt("sequence_no"),
						status = requireNotNull(rs.getString("status")),
					startedAt = startedAt,
					finishedAt = finishedAt,
					durationMs = finishedAt?.let { Duration.between(startedAt, it).toMillis().coerceAtLeast(0) },
					failureCode = rs.getString("failure_code"),
				)
			},
			workspaceId, runId,
		)

		return ArtifactWorkflowRunTimingResponse(
			createdAt = run.createdAt,
			startedAt = run.startedAt,
			finishedAt = run.finishedAt,
			steps = steps,
			model = ArtifactWorkflowModelTimingResponse(run.modelName, run.totalTokens, run.totalLatencyMs),
		)
	}

}

private data class RunTimingRow(
	val createdAt: Instant,
	val startedAt: Instant?,
	val finishedAt: Instant?,
	val modelName: String,
	val totalTokens: Long,
	val totalLatencyMs: Long,
)
