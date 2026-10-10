package com.plot.api.agent

import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.artifact.run.ArtifactRunStatus
import com.plot.api.persistence.SqlExecutor
import com.plot.api.persistence.TransactionExecutor
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Gets agent runs moving again when no worker holds them.
 *
 * Two things strand a RUNNING run: a worker that died while holding the claim,
 * and a run parked on an artifact workflow whose completion signal was lost.
 * Normal turn-by-turn transitions live in [AgentRunExecutionPersistence].
 */
@Component
class AgentRunRecoveryPersistence(
	private val sqlExecutor: SqlExecutor,
	private val transactionExecutor: TransactionExecutor,
	private val queryPersistence: AgentRunQueryPersistence,
	private val executionPersistence: AgentRunExecutionPersistence,
	private val artifactRunPersistence: ArtifactRunPersistence,
	private val clock: Clock? = null,
) {
	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	/**
	 * Releases claims older than [staleBefore] so another worker can retry the run, and fails
	 * runs that have no attempts left. Returns the number of runs released for retry.
	 */
	fun recoverStaleAgentRuns(staleBefore: Instant, now: Instant = currentInstant()): Int {
		executionPersistence.failExhaustedStaleAgentRuns(staleBefore, now)
		return sqlExecutor.update(
			"""
			update agent_runs
			set claimed_by = null, claimed_at = null,
			    transition_version = transition_version + 1, updated_at = ?
			where status = 'RUNNING' and claimed_by is not null and claimed_at < ?
			  and attempt_count < max_attempts
			""".trimIndent(),
			Timestamp.from(now),
			Timestamp.from(staleBefore),
		)
	}

	/**
	 * Finishes the agent run waiting on one artifact workflow, if that workflow is terminal.
	 * Returns false when there is nothing to finish yet or another worker holds the run.
	 */
	fun completeWaitingArtifactHandoff(
		workspaceId: UUID,
		artifactWorkflowRunId: UUID,
		now: Instant = currentInstant(),
	): Boolean = transactionExecutor.execute {
		val agentRunId = sqlExecutor.query(
			"""
			select agent_run_id
			from generation_runs
			where workspace_id = ? and id = ? and agent_run_id is not null
			""".trimIndent(),
			{ rs, _ -> requireNotNull(rs.getObject("agent_run_id", UUID::class.java)) },
			workspaceId,
			artifactWorkflowRunId,
		).firstOrNull() ?: return@execute false
		val run = queryPersistence.findAgentRun(workspaceId, agentRunId) ?: return@execute false
		if (run.status != AgentRunStatus.RUNNING) return@execute false
		val claim = claimRunningAgentRun(run, "artifact-completion-${UUID.randomUUID()}", now)
			?: return@execute false
		val state = artifactRunPersistence.findWorkflowStateByWorkflowRun(workspaceId, artifactWorkflowRunId)
			?: throw IllegalArgumentException("Linked artifact run is unavailable")
		when {
			state.materialized && state.status in setOf(ArtifactRunStatus.READY, ArtifactRunStatus.NEEDS_REVIEW) -> {
				executionPersistence.succeedAgentRun(claim, now)
				true
			}
			state.status == ArtifactRunStatus.FAILED -> {
				executionPersistence.failAgentRun(claim, "AGENT_ARTIFACT_WORKFLOW_FAILED", now)
				true
			}
			else -> {
				releaseArtifactHandoffClaim(claim, now)
				false
			}
		}
	}

	/**
	 * Resumes every agent run parked on an artifact workflow that already reached a
	 * terminal state, so a completion that raced with the handoff or was lost to a
	 * restart still finishes the run.
	 */
	fun reconcileWaitingArtifactHandoffs(now: Instant = currentInstant()): Int {
		val waiting = sqlExecutor.query(
			"""
			select workflow.workspace_id, workflow.id
			from generation_runs workflow
			join artifact_runs artifact
			  on artifact.workspace_id = workflow.workspace_id and artifact.id = workflow.artifact_run_id
			join agent_runs run
			  on run.workspace_id = workflow.workspace_id and run.id = workflow.agent_run_id
			where workflow.agent_run_id is not null
			  and artifact.status in ('READY', 'NEEDS_REVIEW', 'FAILED')
			  and run.status = 'RUNNING' and run.claimed_by is null
			""".trimIndent(),
			{ rs, _ ->
				requireNotNull(rs.getObject("workspace_id", UUID::class.java)) to
					requireNotNull(rs.getObject("id", UUID::class.java))
			},
		)
		return waiting.count { (workspaceId, workflowRunId) ->
			completeWaitingArtifactHandoff(workspaceId, workflowRunId, now)
		}
	}

	private fun claimRunningAgentRun(run: AgentRunRecord, workerId: String, now: Instant): ClaimedAgentRun? {
		val updated = sqlExecutor.update(
			"""
			update agent_runs
			set claimed_by = ?, claimed_at = ?, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and status = 'RUNNING'
			  and claimed_by is null and transition_version = ?
			""".trimIndent(),
			workerId, Timestamp.from(now), Timestamp.from(now), run.workspaceId, run.id, run.transitionVersion,
		)
		if (updated != 1) return null
		return ClaimedAgentRun(
			workspaceId = run.workspaceId,
			agentRunId = run.id,
			transitionVersion = run.transitionVersion + 1,
			workerId = workerId,
		)
	}

	private fun releaseArtifactHandoffClaim(claim: ClaimedAgentRun, now: Instant) {
		sqlExecutor.update(
			"""
			update agent_runs
			set claimed_by = null, claimed_at = null, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ? and status = 'RUNNING'
			""".trimIndent(),
			Timestamp.from(now), claim.workspaceId, claim.agentRunId, claim.workerId, claim.transitionVersion,
		)
	}
}
