package com.plot.api.routine

import com.plot.api.common.UuidGenerator
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate

@Repository
class RoutinePersistence(
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
	private val uuidGenerator: UuidGenerator,
	private val clock: Clock? = null,
) {
	fun list(workspaceId: UUID): List<RoutineRecord> = jdbcTemplate.query(
		selectSql + " where r.workspace_id = ? order by r.created_at desc, r.id desc",
		mapper,
		workspaceId,
	)

	fun find(workspaceId: UUID, id: UUID): RoutineRecord? = jdbcTemplate.query(
		selectSql + " where r.workspace_id = ? and r.id = ?",
		mapper,
		workspaceId,
		id,
	).firstOrNull()

	fun listEnabledGitHubEventRoutines(
		workspaceId: UUID,
		sourceScopeId: UUID,
		cadence: RoutineCadence,
	): List<RoutineRecord> = jdbcTemplate.query(
		selectSql + " where r.workspace_id = ? and r.source_scope_id = ? and r.enabled = true and r.cadence = ? order by r.created_at, r.id",
		mapper,
		workspaceId,
		sourceScopeId,
		cadence.name,
	)

	fun hasEnabledReleaseEventRoutines(workspaceId: UUID, sourceScopeId: UUID): Boolean = jdbcTemplate.queryForObject(
		"select exists(select 1 from routines where workspace_id = ? and source_scope_id = ? and enabled = true and cadence in ('ON_GITHUB_RELEASE', 'ON_GIT_TAG'))",
		Boolean::class.java,
		workspaceId,
		sourceScopeId,
	) == true

	fun insert(
		workspaceId: UUID,
		createdByUserId: UUID,
		name: String,
		sourceScopeId: UUID,
		instruction: String,
		cadence: RoutineCadence,
		now: Instant = currentInstant(),
	): RoutineRecord {
		val id = uuidGenerator.next()
		jdbcTemplate.update(
			"""
			insert into routines (
			  id, workspace_id, created_by_user_id, source_scope_id, name, instruction, cadence,
			  enabled, next_run_at, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?)
			""".trimIndent(),
			id,
			workspaceId,
			createdByUserId,
			sourceScopeId,
			name,
			instruction,
			cadence.name,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
		)
		return requireNotNull(find(workspaceId, id))
	}

	fun update(
		workspaceId: UUID,
		id: UUID,
		name: String,
		sourceScopeId: UUID,
		instruction: String,
		cadence: RoutineCadence,
		enabled: Boolean,
		now: Instant = currentInstant(),
	): RoutineRecord {
		val updated = jdbcTemplate.update(
			"""
			update routines
			set name = ?, source_scope_id = ?, instruction = ?, cadence = ?, enabled = ?, updated_at = ?,
			    transition_version = transition_version + 1
			where workspace_id = ? and id = ?
			""".trimIndent(),
			name,
			sourceScopeId,
			instruction,
			cadence.name,
			enabled,
			Timestamp.from(now),
			workspaceId,
			id,
		)
		check(updated == 1) { "Routine not found" }
		return requireNotNull(find(workspaceId, id))
	}

	fun queueNow(workspaceId: UUID, id: UUID, now: Instant = currentInstant()): RoutineRecord? {
		val updated = jdbcTemplate.update(
			"""
			update routines
			set next_run_at = ?, updated_at = ?, transition_version = transition_version + 1
			where workspace_id = ? and id = ?
			""".trimIndent(),
			Timestamp.from(now),
			Timestamp.from(now),
			workspaceId,
			id,
		)
		return if (updated == 1) find(workspaceId, id) else null
	}

	fun claimNext(workerId: String, now: Instant, staleBefore: Instant): RoutineRecord? = claim(
		workerId = workerId,
		now = now,
		staleBefore = staleBefore,
		where = "r.enabled = true and r.cadence in ('DAILY', 'WEEKLY') and r.next_run_at <= ?",
		args = arrayOf(Timestamp.from(now)),
	)

	fun recordGitHubEventRun(routine: RoutineRecord, generationRunId: UUID, now: Instant) {
		val updated = jdbcTemplate.update(
			"""
			update routines
			set last_run_at = ?, last_generation_run_id = ?, last_run_status = 'QUEUED',
			    last_error_code = null, transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and enabled = true and cadence = ?
			""".trimIndent(),
			Timestamp.from(now),
			generationRunId,
			Timestamp.from(now),
			routine.workspaceId,
			routine.id,
			routine.cadence.name,
		)
		check(updated == 1) { "GitHub event routine is no longer enabled" }
	}

	fun claimById(workerId: String, workspaceId: UUID, id: UUID, now: Instant, staleBefore: Instant): RoutineRecord? = claim(
		workerId = workerId,
		now = now,
		staleBefore = staleBefore,
		where = "r.workspace_id = ? and r.id = ? and r.next_run_at <= ?",
		args = arrayOf(workspaceId, id, Timestamp.from(now)),
	)

	fun finish(
		claim: RoutineRecord,
		now: Instant,
		nextRunAt: Instant,
		status: String,
		generationRunId: UUID? = null,
		errorCode: String? = null,
		advanceCursor: Boolean = true,
	) {
		val updated = jdbcTemplate.update(
			"""
			update routines
			set last_run_at = case when ? then ? else last_run_at end,
			    next_run_at = ?, last_generation_run_id = coalesce(?, last_generation_run_id),
			    last_run_status = ?, last_error_code = ?, claimed_by = null, claimed_at = null,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
			""".trimIndent(),
			advanceCursor,
			Timestamp.from(now),
			Timestamp.from(nextRunAt),
			generationRunId,
			status,
			errorCode,
			Timestamp.from(now),
			claim.workspaceId,
			claim.id,
			claim.claimedBy,
			claim.transitionVersion,
		)
		check(updated == 1) { "Routine claim was lost" }
	}

	private fun claim(
		workerId: String,
		now: Instant,
		staleBefore: Instant,
		where: String,
		args: Array<Any>,
	): RoutineRecord? = transactionTemplate.execute {
		val row = jdbcTemplate.query(
				claimSelectSql + " where $where and (r.claimed_by is null or r.claimed_at < ?) order by r.next_run_at, r.id for update skip locked limit 1",
				mapper,
				*args,
				Timestamp.from(staleBefore),
			).firstOrNull() ?: return@execute null
			val claimedAt = Timestamp.from(now)
			val updated = jdbcTemplate.update(
				"""
				update routines
				set claimed_by = ?, claimed_at = ?, transition_version = transition_version + 1, updated_at = ?
				where workspace_id = ? and id = ? and transition_version = ?
				""".trimIndent(),
				workerId,
				claimedAt,
				claimedAt,
				row.workspaceId,
				row.id,
				row.transitionVersion,
			)
			if (updated != 1) null else find(row.workspaceId, row.id)
		}

	private fun ResultSet.toRoutine(): RoutineRecord = RoutineRecord(
		id = getObject("id", UUID::class.java),
		workspaceId = getObject("workspace_id", UUID::class.java),
		createdByUserId = getObject("created_by_user_id", UUID::class.java),
		sourceScopeId = getObject("source_scope_id", UUID::class.java),
		sourceLabel = getString("source_label"),
		name = getString("name"),
		instruction = getString("instruction"),
		cadence = RoutineCadence.valueOf(getString("cadence")),
		enabled = getBoolean("enabled"),
		lastRunAt = getTimestamp("last_run_at")?.toInstant(),
		nextRunAt = getTimestamp("next_run_at").toInstant(),
		lastGenerationRunId = getObject("last_generation_run_id", UUID::class.java),
		lastRunStatus = getString("effective_run_status"),
		lastErrorCode = getString("effective_error_code"),
		claimedBy = getString("claimed_by"),
		claimedAt = getTimestamp("claimed_at")?.toInstant(),
		transitionVersion = getLong("transition_version"),
		createdAt = getTimestamp("created_at").toInstant(),
		updatedAt = getTimestamp("updated_at").toInstant(),
	)

	private val mapper = { rs: ResultSet, _: Int -> rs.toRoutine() }

	private fun currentInstant(): Instant = clock?.instant() ?: Instant.now()

	private val selectSql = """
		select r.id, r.workspace_id, r.created_by_user_id, r.source_scope_id, s.display_name as source_label,
		       r.name, r.instruction, r.cadence, r.enabled, r.last_run_at, r.next_run_at,
		       r.last_generation_run_id,
		       case when r.last_run_status = 'NO_ACTIVITY' then r.last_run_status
		            else coalesce(gr.status, r.last_run_status) end as effective_run_status,
		       coalesce(gr.error_code, r.last_error_code) as effective_error_code, r.claimed_by, r.claimed_at,
		       r.transition_version, r.created_at, r.updated_at
		from routines r
		join source_scopes s on s.workspace_id = r.workspace_id and s.id = r.source_scope_id
		left join generation_runs gr on gr.workspace_id = r.workspace_id and gr.id = r.last_generation_run_id
	""".trimIndent()

	private val claimSelectSql = """
		select r.id, r.workspace_id, r.created_by_user_id, r.source_scope_id, s.display_name as source_label,
		       r.name, r.instruction, r.cadence, r.enabled, r.last_run_at, r.next_run_at,
		       r.last_generation_run_id, r.last_run_status as effective_run_status,
		       r.last_error_code as effective_error_code, r.claimed_by, r.claimed_at,
		       r.transition_version, r.created_at, r.updated_at
		from routines r
		join source_scopes s on s.workspace_id = r.workspace_id and s.id = r.source_scope_id
	""".trimIndent()
}
