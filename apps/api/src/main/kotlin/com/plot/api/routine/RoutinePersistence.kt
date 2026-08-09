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

	fun updateEnabled(
		workspaceId: UUID,
		id: UUID,
		enabled: Boolean,
		now: Instant = currentInstant(),
	): RoutineRecord? {
		val updated = jdbcTemplate.update(
			"""
			update routines
			set enabled = ?, updated_at = ?,
			    transition_version = transition_version + 1
			where workspace_id = ? and id = ? and claimed_by is null
			""".trimIndent(),
			enabled,
			Timestamp.from(now),
			workspaceId,
			id,
		)
		return if (updated == 1) find(workspaceId, id) else null
	}

	fun queueManual(workspaceId: UUID, id: UUID, executionId: UUID): RoutineRecord? {
		val updated = jdbcTemplate.update(
			"""
			update routines
			set active_execution_id = ?, updated_at = ?, transition_version = transition_version + 1
			where workspace_id = ? and id = ? and claimed_by is null
			  and (active_execution_id is null or active_execution_id = ?)
			""".trimIndent(),
			executionId,
			Timestamp.from(currentInstant()),
			workspaceId,
			id,
			executionId,
		)
		return if (updated == 1) find(workspaceId, id) else null
	}

	fun claimNext(workerId: String, now: Instant, staleBefore: Instant): RoutineRecord? = claim(
		workerId = workerId,
		now = now,
		staleBefore = staleBefore,
		where = "(r.enabled = true and r.cadence in ('DAILY', 'WEEKLY') and r.next_run_at <= ?)",
		args = arrayOf(Timestamp.from(now)),
	)

	fun isSourceActive(workspaceId: UUID, sourceScopeId: UUID): Boolean = jdbcTemplate.queryForObject(
		"""
		select exists(
		  select 1
		  from source_scopes scope
		  join source_namespaces namespace
		    on namespace.workspace_id = scope.workspace_id
		   and namespace.id = scope.source_namespace_id
		   and namespace.provider = scope.provider
		   and namespace.status = 'ACTIVE'
		  join connection_namespace_bindings binding
		    on binding.workspace_id = namespace.workspace_id
		   and binding.source_namespace_id = namespace.id
		   and binding.provider = namespace.provider
		   and binding.status = 'ACTIVE'
		  join connections connection
		    on connection.workspace_id = binding.workspace_id
		   and connection.id = binding.connection_id
		   and connection.provider = binding.provider
		   and connection.status = 'ACTIVE'
		  where scope.workspace_id = ? and scope.id = ? and scope.status = 'ACTIVE'
		)
		""".trimIndent(),
		Boolean::class.java,
		workspaceId,
		sourceScopeId,
	) == true

	fun lockWorkspaceActivity(workspaceId: UUID) {
		jdbcTemplate.query(
			"select pg_advisory_xact_lock(hashtextextended(?, 0))",
			{ _, _ -> Unit },
			"routine-activity:$workspaceId",
		)
	}

	fun finish(
		claim: RoutineRecord,
		now: Instant,
		nextRunAt: Instant,
		status: String,
		generationRunId: UUID? = null,
		errorCode: String? = null,
		activityCursor: RoutineActivityCursor? = null,
	) {
		val executionId = requireNotNull(claim.activeExecutionId) { "Routine claim has no execution identity" }
		val updated = jdbcTemplate.update(
			"""
			update routines
			set last_run_at = ?, next_run_at = ?, last_execution_id = ?, last_generation_run_id = ?,
			    last_run_status = ?, last_error_code = ?,
			    activity_cursor_sequence = case when ? then ? else activity_cursor_sequence end,
			    active_execution_id = null, claimed_by = null, claimed_at = null,
			    transition_version = transition_version + 1, updated_at = ?
			where workspace_id = ? and id = ? and claimed_by = ? and transition_version = ?
			""".trimIndent(),
			Timestamp.from(now),
			Timestamp.from(nextRunAt),
			executionId,
			generationRunId,
			status,
			errorCode,
			activityCursor != null,
			activityCursor?.sequence,
			Timestamp.from(now),
			claim.workspaceId,
			claim.id,
			claim.claimedBy,
			claim.transitionVersion,
		)
		if (updated != 1) throw RoutineClaimLostException()
	}

	private fun claim(
		workerId: String,
		now: Instant,
		staleBefore: Instant,
		where: String,
		args: Array<Any>,
	): RoutineRecord? = transactionTemplate.execute {
		val row = jdbcTemplate.query(
				claimSelectSql + "\n" + """
					where $where
					and (r.claimed_by is null or r.claimed_at < ?)
					order by r.next_run_at, r.id for update skip locked limit 1
				""".trimIndent(),
				mapper,
				*args,
				Timestamp.from(staleBefore),
			).firstOrNull() ?: return@execute null
			val claimedAt = Timestamp.from(now)
			val executionId = row.activeExecutionId ?: uuidGenerator.next()
			val updated = jdbcTemplate.update(
				"""
				update routines
				set claimed_by = ?, claimed_at = ?, active_execution_id = ?, last_execution_id = ?,
				    last_run_at = ?, last_generation_run_id = null, last_run_status = 'QUEUED',
				    last_error_code = null, transition_version = transition_version + 1, updated_at = ?
				where workspace_id = ? and id = ? and transition_version = ?
				""".trimIndent(),
				workerId,
				claimedAt,
				executionId,
				executionId,
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
		activityCursorSequence = getObject("activity_cursor_sequence", java.lang.Long::class.java)?.toLong(),
		lastRunAt = getTimestamp("last_run_at")?.toInstant(),
		nextRunAt = getTimestamp("next_run_at").toInstant(),
		activeExecutionId = getObject("active_execution_id", UUID::class.java),
		lastExecutionId = getObject("last_execution_id", UUID::class.java),
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
		       r.name, r.instruction, r.cadence, r.enabled, r.activity_cursor_sequence,
		       r.last_run_at, r.next_run_at, r.active_execution_id, r.last_execution_id,
		       r.last_generation_run_id,
		       case when r.last_generation_run_id is null then r.last_run_status
		            else coalesce(gr.status, r.last_run_status) end as effective_run_status,
		       case when r.last_generation_run_id is null then r.last_error_code
		            else coalesce(gr.error_code, r.last_error_code) end as effective_error_code,
		       r.claimed_by, r.claimed_at,
		       r.transition_version, r.created_at, r.updated_at
		from routines r
		join source_scopes s on s.workspace_id = r.workspace_id and s.id = r.source_scope_id
		left join generation_runs gr on gr.workspace_id = r.workspace_id and gr.id = r.last_generation_run_id
	""".trimIndent()

	private val claimSelectSql = """
		select r.id, r.workspace_id, r.created_by_user_id, r.source_scope_id, s.display_name as source_label,
		       r.name, r.instruction, r.cadence, r.enabled, r.activity_cursor_sequence,
		       r.last_run_at, r.next_run_at, r.active_execution_id, r.last_execution_id,
		       r.last_generation_run_id, r.last_run_status as effective_run_status,
		       r.last_error_code as effective_error_code, r.claimed_by, r.claimed_at,
		       r.transition_version, r.created_at, r.updated_at
		from routines r
		join source_scopes s on s.workspace_id = r.workspace_id and s.id = r.source_scope_id
	""".trimIndent()
}
