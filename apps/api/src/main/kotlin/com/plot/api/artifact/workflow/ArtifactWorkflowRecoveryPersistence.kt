package com.plot.api.artifact.workflow

import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

class ArtifactWorkflowRecoveryPersistence(
	private val sqlExecutor: JooqSqlExecutor,
	private val transactionExecutor: JooqTransactionExecutor,
	private val clock: Clock = Clock.systemUTC(),
) {
	/** Earliest persisted invocation retry, including rows already due, or null when none is pending. */
	fun earliestNextAttemptAt(): Instant? = sqlExecutor.query(
		"""
		select min(next_attempt_at) as next_attempt_at
		from generation_runs
		where status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
		  and claimed_by is null and next_attempt_at is not null
		""".trimIndent(),
		{ rs, _ -> rs.getTimestamp("next_attempt_at")?.toInstant() },
	).firstOrNull()

	fun recoverStaleClaims(staleBefore: Instant): Int = transactionExecutor.execute {
		val now = clock.instant()
		val candidates = sqlExecutor.query(
			"""
			select workspace_id, id, claimed_by, transition_version
			from generation_runs
			where claimed_by is not null and (heartbeat_at is null or heartbeat_at < ?)
			  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			order by heartbeat_at nulls first, id
			for update skip locked
			""".trimIndent(),
			{ rs, _ ->
				StaleArtifactWorkflowClaim(
					workspaceId = requireNotNull(rs.getObject("workspace_id", UUID::class.java)),
					runId = requireNotNull(rs.getObject("id", UUID::class.java)),
					workerId = requireNotNull(rs.getString("claimed_by")),
					transitionVersion = rs.getLong("transition_version"),
				)
			},
			Timestamp.from(staleBefore),
		)
		candidates.sumOf { candidate ->
			sqlExecutor.update(
				"""
				update model_invocations
				set status = 'FAILED', failure_code = 'LEASE_LOST_OUTCOME_UNKNOWN', finished_at = ?
				where workspace_id = ? and generation_run_id = ? and status = 'RUNNING'
				""".trimIndent(),
				Timestamp.from(now),
				candidate.workspaceId,
				candidate.runId,
			)
			val updated = sqlExecutor.update(
				"""
				update generation_runs
				set claimed_by = null, claimed_at = null, heartbeat_at = null, next_attempt_at = null,
				    transition_version = transition_version + 1, updated_at = ?
				where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
				  and (heartbeat_at is null or heartbeat_at < ?)
				  and status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
				""".trimIndent(),
				Timestamp.from(now),
				candidate.workspaceId,
				candidate.runId,
				candidate.workerId,
				candidate.transitionVersion,
				Timestamp.from(staleBefore),
			)
			when (updated) {
				0 -> 0
				1 -> 1
				else -> error("Stale artifact workflow claim recovery updated $updated rows")
			}
		}
	}

}

private data class StaleArtifactWorkflowClaim(
	val workspaceId: UUID,
	val runId: UUID,
	val workerId: String,
	val transitionVersion: Long,
)
