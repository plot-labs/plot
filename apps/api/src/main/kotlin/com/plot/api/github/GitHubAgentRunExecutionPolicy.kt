package com.plot.api.github

import com.plot.api.agent.AgentRunExecutionPolicy
import com.plot.api.persistence.SqlExecutor
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class GitHubAgentRunExecutionPolicy(private val sqlExecutor: SqlExecutor) : AgentRunExecutionPolicy {
	override fun isReleaseRun(workspaceId: UUID, agentRunId: UUID): Boolean = sqlExecutor.queryForObject(
		"select exists(select 1 from github_release_draft_requests where workspace_id = ? and agent_run_id = ?)",
		Boolean::class.java, workspaceId, agentRunId,
	) == true

	override fun releaseRoutineGateFailure(workspaceId: UUID, agentRunId: UUID): String? = sqlExecutor.query(
		"""
		select case when not routine.enabled then 'ROUTINE_DISABLED'
		  when execution.release_request_id is null then 'GITHUB_RELEASE_RANGE_REQUIRED'
		  when release.agent_run_id is distinct from agent.id or release.status <> 'GENERATING'
		    then 'GITHUB_RELEASE_REQUEST_INACTIVE' end as failure
		from agent_runs agent
		join routines routine on routine.workspace_id = agent.workspace_id and routine.id = agent.routine_id
		join routine_executions execution on execution.workspace_id = agent.workspace_id and execution.id = agent.routine_execution_id
		left join github_release_draft_requests release on release.workspace_id = execution.workspace_id and release.id = execution.release_request_id
		where agent.workspace_id = ? and agent.id = ? and routine.cadence in ('ON_GIT_TAG', 'ON_GITHUB_RELEASE')
		""".trimIndent(),
		{ row, _ -> row.getString("failure") }, workspaceId, agentRunId,
	).firstOrNull()

}
