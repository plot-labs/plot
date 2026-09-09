package com.plot.api.recovery

import com.plot.api.persistence.JooqSqlExecutor
import java.sql.Timestamp
import java.time.Instant
import org.springframework.stereotype.Component

data class QueueRunnableStats(
	val runnableCount: Int,
	val oldestCreatedAt: Instant?,
)

@Component
open class RecoveryCoordinatorPersistence(
	private val sqlExecutor: JooqSqlExecutor,
) {
	open fun findReleaseDraftStats(now: Instant, staleBefore: Instant, batchSize: Int): QueueRunnableStats =
		sqlExecutor.query(
			"""
			select count(*) as cnt, min(sub.created_at) as oldest
			from (
			  select candidate.created_at
			  from github_release_draft_requests candidate
			  where (
			    (candidate.status in ('QUEUED', 'RESOLVING')
			      and (candidate.next_attempt_at is null or candidate.next_attempt_at <= ?)
			      and (candidate.claimed_by is null or candidate.heartbeat_at is null or candidate.heartbeat_at < ?))
			    or
			    (candidate.status = 'GENERATING'
			      and candidate.claimed_by is not null
			      and (candidate.heartbeat_at is null or candidate.heartbeat_at < ?))
			  )
			  and exists (
			    select 1 from source_scopes scope
			    where scope.workspace_id = candidate.workspace_id
			      and scope.id = candidate.source_scope_id
			      and scope.status = 'ACTIVE'
			  )
			  and not exists (
			    select 1 from github_release_draft_requests predecessor
			    where predecessor.workspace_id = candidate.workspace_id
			      and predecessor.source_scope_id = candidate.source_scope_id
			      and predecessor.routine_id is not distinct from candidate.routine_id
			      and (predecessor.created_at, predecessor.id) < (candidate.created_at, candidate.id)
			      and predecessor.status not in ('READY', 'NO_ACTIVITY', 'NEEDS_RANGE', 'FAILED')
			  )
			  order by candidate.created_at, candidate.id
			  limit ?
			) sub
			""".trimIndent(),
			{ rs, _ ->
				QueueRunnableStats(
					runnableCount = rs.getInt("cnt"),
					oldestCreatedAt = rs.getTimestamp("oldest")?.toInstant(),
				)
			},
			Timestamp.from(now),
			Timestamp.from(staleBefore),
			Timestamp.from(staleBefore),
			batchSize,
		).firstOrNull() ?: QueueRunnableStats(0, null)

	open fun findRoutineExecutionStats(now: Instant, staleBefore: Instant, batchSize: Int): QueueRunnableStats =
		sqlExecutor.query(
			"""
			select count(*) as cnt, min(sub.created_at) as oldest
			from (
			  select candidate.created_at
			  from routine_executions candidate
			  where candidate.status in ('QUEUED', 'PROBING')
			    and (candidate.next_attempt_at is null or candidate.next_attempt_at <= ?)
			    and (candidate.claimed_by is null or candidate.claimed_at is null or candidate.claimed_at < ?)
			  order by candidate.created_at, candidate.id
			  limit ?
			) sub
			""".trimIndent(),
			{ rs, _ ->
				QueueRunnableStats(
					runnableCount = rs.getInt("cnt"),
					oldestCreatedAt = rs.getTimestamp("oldest")?.toInstant(),
				)
			},
			Timestamp.from(now),
			Timestamp.from(staleBefore),
			batchSize,
		).firstOrNull() ?: QueueRunnableStats(0, null)

	open fun findAgentRunStats(now: Instant, staleBefore: Instant, batchSize: Int): QueueRunnableStats =
		sqlExecutor.query(
			"""
			select count(*) as cnt, min(sub.created_at) as oldest
			from (
			  select candidate.created_at
			  from agent_runs candidate
			  where (
			    (candidate.status in ('QUEUED', 'RUNNING')
			      and candidate.attempt_count < candidate.max_attempts
			      and (candidate.next_attempt_at is null or candidate.next_attempt_at <= ?)
			      and (candidate.claimed_by is null or candidate.claimed_at is null or candidate.claimed_at < ?))
			    or
			    (candidate.status = 'RUNNING' and candidate.claimed_by is null
			      and exists (
			        select 1 from generation_runs workflow
			        join artifact_runs artifact
			          on artifact.workspace_id = workflow.workspace_id and artifact.id = workflow.artifact_run_id
			        where workflow.workspace_id = candidate.workspace_id
			          and workflow.agent_run_id = candidate.id
			          and artifact.status in ('READY', 'NEEDS_REVIEW', 'FAILED')
			      ))
			  )
			  order by candidate.created_at, candidate.id
			  limit ?
			) sub
			""".trimIndent(),
			{ rs, _ ->
				QueueRunnableStats(
					runnableCount = rs.getInt("cnt"),
					oldestCreatedAt = rs.getTimestamp("oldest")?.toInstant(),
				)
			},
			Timestamp.from(now),
			Timestamp.from(staleBefore),
			batchSize,
		).firstOrNull() ?: QueueRunnableStats(0, null)

	open fun findArtifactWorkflowStats(now: Instant, staleBefore: Instant, batchSize: Int): QueueRunnableStats =
		sqlExecutor.query(
			"""
			select count(*) as cnt, min(sub.created_at) as oldest
			from (
			  select candidate.created_at
			  from generation_runs candidate
			  where candidate.status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			    and (candidate.next_attempt_at is null or candidate.next_attempt_at <= ?)
			    and (candidate.claimed_by is null or candidate.heartbeat_at is null or candidate.heartbeat_at < ?)
			  order by candidate.created_at, candidate.id
			  limit ?
			) sub
			""".trimIndent(),
			{ rs, _ ->
				QueueRunnableStats(
					runnableCount = rs.getInt("cnt"),
					oldestCreatedAt = rs.getTimestamp("oldest")?.toInstant(),
				)
			},
			Timestamp.from(now),
			Timestamp.from(staleBefore),
			batchSize,
		).firstOrNull() ?: QueueRunnableStats(0, null)
}
