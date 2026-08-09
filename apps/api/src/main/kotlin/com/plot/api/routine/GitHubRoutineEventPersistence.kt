package com.plot.api.routine

import com.plot.api.common.UuidGenerator
import com.plot.api.github.GitHubWebhookDelivery
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate

@Repository
class GitHubRoutineEventPersistence(
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
	private val uuidGenerator: UuidGenerator,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun enqueue(
		routine: RoutineRecord,
		delivery: GitHubWebhookDelivery,
		writingBlockIds: List<UUID>,
	): GitHubRoutineEventRun? {
		require(writingBlockIds.isNotEmpty()) { "GitHub routine event evidence cannot be empty" }
		require(writingBlockIds.distinct().size == writingBlockIds.size) {
			"GitHub routine event evidence IDs must be unique"
		}
		return transactionTemplate.execute {
			find(routine.id, delivery.id)?.let { return@execute it }
			val routineLock = lockRoutineForEvent(routine) ?: return@execute null
			val eventRunId = uuidGenerator.next()
			val now = clock.instant()
			val inserted = jdbcTemplate.update(
				"""
				insert into routine_github_event_runs (
				 id, workspace_id, routine_id, delivery_id, status, created_at, updated_at
				)
				select ?, r.workspace_id, r.id, ?, 'QUEUED', ?, ?
				from routines r
				where r.workspace_id = ? and r.id = ? and r.enabled = true and r.cadence = ?
				on conflict (routine_id, delivery_id) do nothing
				""".trimIndent(),
				eventRunId,
				delivery.id,
				Timestamp.from(now),
				Timestamp.from(now),
				routine.workspaceId,
				routine.id,
				routine.cadence.name,
			)
			if (inserted == 0) return@execute find(routine.id, delivery.id)
			writingBlockIds.forEachIndexed { index, writingBlockId ->
				val evidenceInserted = jdbcTemplate.update(
					"""
					insert into routine_github_event_evidence
					(event_run_id, workspace_id, writing_block_id, writing_block_activity_sequence, order_index)
					select ?, block.workspace_id, block.id, block.activity_sequence, ?
					from writing_blocks block
					where block.workspace_id = ? and block.id = ?
					""".trimIndent(),
					eventRunId,
					index,
					routine.workspaceId,
					writingBlockId,
				)
				check(evidenceInserted == 1) { "GitHub routine event evidence was not inserted" }
			}
			if (routineLock.claimedBy == null) {
				projectQueued(routine, delivery, eventRunId, routineLock.transitionVersion, now)
			}
			requireNotNull(find(eventRunId))
		}
	}

	fun claimNext(workerId: String, now: Instant, claimTimeout: Duration): GitHubRoutineEventRun? =
		transactionTemplate.execute {
			val staleBefore = now.minus(claimTimeout)
			val candidate = jdbcTemplate.query(
				selectSql + "\n" + """
					 where (e.status = 'QUEUED'
					    or (e.status = 'PROCESSING' and e.claimed_at < ?))
					   and (r.claimed_by is null or r.claimed_by = 'routine-github:' || e.id::text)
				   and not exists (
				     select 1
				     from routine_github_event_runs predecessor
				     join github_webhook_deliveries predecessor_delivery
				       on predecessor_delivery.id = predecessor.delivery_id
				     where predecessor.routine_id = e.routine_id
				       and predecessor.status in ('QUEUED', 'PROCESSING')
				       and (predecessor_delivery.received_at, predecessor.id) < (d.received_at, e.id)
				   )
				 order by d.received_at, e.id
				 for update of e, r skip locked
				 limit 1
				""".trimIndent(),
				mapper,
				Timestamp.from(staleBefore),
			).firstOrNull() ?: return@execute null
			val updated = jdbcTemplate.update(
				"""
				update routine_github_event_runs
				set status = 'PROCESSING', attempt_count = attempt_count + 1,
				    claimed_by = ?, claimed_at = ?, transition_version = transition_version + 1,
				    error_code = null, updated_at = ?
				where id = ? and transition_version = ?
				  and (status = 'QUEUED' or (status = 'PROCESSING' and claimed_at < ?))
				""".trimIndent(),
				workerId,
				Timestamp.from(now),
				Timestamp.from(now),
				candidate.id,
				candidate.transitionVersion,
				Timestamp.from(staleBefore),
			)
			if (updated != 1) return@execute null
			claimRoutine(candidate, now)
			projectClaimed(candidate, now)
			requireNotNull(find(candidate.id))
		}

	fun succeed(item: GitHubRoutineEventRun, generationRunId: UUID, now: Instant) {
		transactionTemplate.executeWithoutResult {
			finish(item, GitHubRoutineEventStatus.SUCCEEDED, generationRunId, errorCode = null, now = now)
			releaseRoutineClaim(item, generationRunId, status = "QUEUED", errorCode = null, now = now)
		}
	}

	fun fail(item: GitHubRoutineEventRun, errorCode: String, now: Instant) {
		require(errorCode.matches(SAFE_ERROR_CODE)) { "GitHub routine event error code is invalid" }
		transactionTemplate.executeWithoutResult {
			finish(item, GitHubRoutineEventStatus.FAILED, generationRunId = null, errorCode = errorCode, now = now)
			releaseRoutineClaim(item, generationRunId = null, status = "FAILED", errorCode = errorCode, now = now)
		}
	}

	fun find(id: UUID): GitHubRoutineEventRun? = jdbcTemplate.query(
		selectSql + " where e.id = ?",
		mapper,
		id,
	).firstOrNull()?.withEvidence()

	private fun find(routineId: UUID, deliveryId: UUID): GitHubRoutineEventRun? = jdbcTemplate.query(
		selectSql + " where e.routine_id = ? and e.delivery_id = ?",
		mapper,
		routineId,
		deliveryId,
	).firstOrNull()?.withEvidence()

	private fun projectQueued(
		routine: RoutineRecord,
		delivery: GitHubWebhookDelivery,
		eventRunId: UUID,
		transitionVersion: Long,
		now: Instant,
	) {
		jdbcTemplate.update(
			"""
			update routines
			set last_run_at = ?, last_execution_id = ?, last_generation_run_id = null,
			    last_run_status = 'QUEUED', last_error_code = null,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by is null and transition_version = ?
			  and (
			    last_run_at is null or last_run_at < ?
			    or (last_run_at = ? and (last_execution_id is null or last_execution_id < ?))
			  )
			""".trimIndent(),
			Timestamp.from(delivery.receivedAt),
			eventRunId,
			Timestamp.from(now),
			routine.workspaceId,
			routine.id,
			transitionVersion,
			Timestamp.from(delivery.receivedAt),
			Timestamp.from(delivery.receivedAt),
			eventRunId,
		)
	}

	private fun projectClaimed(item: GitHubRoutineEventRun, now: Instant) {
		jdbcTemplate.update(
			"""
			update routines
			set last_run_at = ?, last_execution_id = ?, last_generation_run_id = null,
			    last_run_status = 'QUEUED', last_error_code = null,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ?
			  and (
			    last_execution_id = ? or last_run_at is null or last_run_at < ?
			    or (last_run_at = ? and (last_execution_id is null or last_execution_id < ?))
			  )
			""".trimIndent(),
			Timestamp.from(item.receivedAt),
			item.id,
			Timestamp.from(now),
			item.workspaceId,
			item.routineId,
			claimMarker(item.id),
			item.id,
			Timestamp.from(item.receivedAt),
			Timestamp.from(item.receivedAt),
			item.id,
		)
	}

	private fun claimRoutine(item: GitHubRoutineEventRun, now: Instant) {
		val marker = claimMarker(item.id)
		val updated = jdbcTemplate.update(
			"""
			update routines
			set claimed_by = ?, claimed_at = ?, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and (claimed_by is null or claimed_by = ?)
			""".trimIndent(),
			marker,
			Timestamp.from(now),
			Timestamp.from(now),
			item.workspaceId,
			item.routineId,
			marker,
		)
		if (updated != 1) throw GitHubRoutineEventClaimLostException()
	}

	private fun lockRoutineForEvent(routine: RoutineRecord): RoutineProjectionLock? = jdbcTemplate.query(
		"""
		select transition_version, claimed_by
		from routines
		where workspace_id = ? and id = ? and enabled = true and cadence = ?
		for update
		""".trimIndent(),
		{ rs, _ -> RoutineProjectionLock(rs.getLong("transition_version"), rs.getString("claimed_by")) },
		routine.workspaceId,
		routine.id,
		routine.cadence.name,
	).firstOrNull()

	private fun finish(
		item: GitHubRoutineEventRun,
		status: GitHubRoutineEventStatus,
		generationRunId: UUID?,
		errorCode: String?,
		now: Instant,
	) {
		val updated = jdbcTemplate.update(
			"""
			update routine_github_event_runs
			set status = ?, generation_run_id = ?, error_code = ?, claimed_by = null, claimed_at = null,
			    transition_version = transition_version + 1, finished_at = ?, updated_at = ?
			where id = ? and transition_version = ? and status = 'PROCESSING' and claimed_by = ?
			""".trimIndent(),
			status.name,
			generationRunId,
			errorCode,
			Timestamp.from(now),
			Timestamp.from(now),
			item.id,
			item.transitionVersion,
			item.claimedBy,
		)
		if (updated != 1) throw GitHubRoutineEventClaimLostException()
	}

	private fun releaseRoutineClaim(
		item: GitHubRoutineEventRun,
		generationRunId: UUID?,
		status: String,
		errorCode: String?,
		now: Instant,
	) {
		val updated = jdbcTemplate.update(
			"""
			update routines
			set last_generation_run_id = case when last_execution_id = ? then ? else last_generation_run_id end,
			    last_run_status = case when last_execution_id = ? then ? else last_run_status end,
			    last_error_code = case when last_execution_id = ? then ? else last_error_code end,
			    claimed_by = null, claimed_at = null,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ?
			""".trimIndent(),
			item.id,
			generationRunId,
			item.id,
			status,
			item.id,
			errorCode,
			Timestamp.from(now),
			item.workspaceId,
			item.routineId,
			claimMarker(item.id),
		)
		if (updated != 1) throw GitHubRoutineEventClaimLostException()
	}

	private fun GitHubRoutineEventRun.withEvidence(): GitHubRoutineEventRun = copy(
		evidence = jdbcTemplate.query(
			"""
			select writing_block_id, writing_block_activity_sequence
			from routine_github_event_evidence
			where event_run_id = ? order by order_index
			""".trimIndent(),
			{ rs, _ -> GitHubRoutineEventEvidence(
				rs.getObject("writing_block_id", UUID::class.java),
				rs.getLong("writing_block_activity_sequence"),
			) },
			id,
		),
	)

	private fun ResultSet.toEventRun() = GitHubRoutineEventRun(
		id = getObject("id", UUID::class.java),
		workspaceId = getObject("workspace_id", UUID::class.java),
		routineId = getObject("routine_id", UUID::class.java),
		deliveryId = getObject("delivery_id", UUID::class.java),
		externalDeliveryId = getString("external_delivery_id"),
		receivedAt = getTimestamp("received_at").toInstant(),
		status = GitHubRoutineEventStatus.valueOf(getString("status")),
		attemptCount = getInt("attempt_count"),
		transitionVersion = getLong("transition_version"),
		generationRunId = getObject("generation_run_id", UUID::class.java),
		errorCode = getString("error_code"),
		claimedBy = getString("claimed_by"),
		claimedAt = getTimestamp("claimed_at")?.toInstant(),
		evidence = emptyList(),
		createdAt = getTimestamp("created_at").toInstant(),
		updatedAt = getTimestamp("updated_at").toInstant(),
	)

	private val mapper = { rs: ResultSet, _: Int -> rs.toEventRun() }

	private val selectSql = """
		select e.id, e.workspace_id, e.routine_id, e.delivery_id,
		       d.external_delivery_id, d.received_at, e.status, e.attempt_count,
		       e.transition_version, e.generation_run_id, e.error_code, e.claimed_by, e.claimed_at,
		       e.created_at, e.updated_at
		from routine_github_event_runs e
		join github_webhook_deliveries d on d.id = e.delivery_id
		join routines r on r.workspace_id = e.workspace_id and r.id = e.routine_id
	""".trimIndent()

	private companion object {
		val SAFE_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,99}")
		fun claimMarker(eventRunId: UUID) = "routine-github:$eventRunId"
	}
}

private data class RoutineProjectionLock(
	val transitionVersion: Long,
	val claimedBy: String?,
)
