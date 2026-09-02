package com.plot.api.github

import com.plot.api.persistence.JooqSqlExecutor
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class GitHubReleaseReconciliationTrigger(
	private val requestPersistence: GitHubReleaseRequestStore,
	private val releaseDispatcher: GitHubReleaseDraftDispatcher,
	private val properties: GitHubProperties,
	private val sqlExecutor: JooqSqlExecutor,
) {
	fun afterAgentRunTerminal(workspaceId: UUID, agentRunId: UUID) {
		if (!properties.releaseAutomationEnabled) return
		if (requestPersistence.hasGeneratingRequestForAgentRun(workspaceId, agentRunId)) {
			releaseDispatcher.dispatch()
		}
	}

	fun afterArtifactWorkflowTerminal(workspaceId: UUID, workflowRunId: UUID) {
		if (!properties.releaseAutomationEnabled) return
		val agentRunId = sqlExecutor.query(
			"""
			select agent_run_id
			from generation_runs
			where workspace_id = ? and id = ? and agent_run_id is not null
			""".trimIndent(),
			{ rs, _ -> requireNotNull(rs.getObject("agent_run_id", UUID::class.java)) },
			workspaceId,
			workflowRunId,
		).firstOrNull() ?: return
		afterAgentRunTerminal(workspaceId, agentRunId)
	}
}
