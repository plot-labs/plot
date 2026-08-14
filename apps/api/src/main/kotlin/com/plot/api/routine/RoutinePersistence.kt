package com.plot.api.routine

import com.plot.api.common.UuidGenerator
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class RoutinePersistence(
	private val dsl: DSLContext,
	private val uuidGenerator: UuidGenerator,
	private val clock: Clock? = null,
) {
	fun list(workspaceId: UUID): List<RoutineRecord> = fetchRows(
		selectSql + " where r.workspace_id = ? order by r.created_at desc, r.id desc",
		workspaceId,
	).map { it.toRoutine() }

	fun find(workspaceId: UUID, id: UUID): RoutineRecord? = fetchRows(
		selectSql + " where r.workspace_id = ? and r.id = ?",
		workspaceId,
		id,
	).firstOrNull()?.toRoutine()

	fun findForUpdate(workspaceId: UUID, id: UUID): RoutineRecord? = fetchRows(
		selectSql + " where r.workspace_id = ? and r.id = ? for update of r",
		workspaceId,
		id,
	).firstOrNull()?.toRoutine()

	fun listEnabledGitHubEventRoutines(
		workspaceId: UUID,
		sourceScopeId: UUID,
		cadence: RoutineCadence,
	): List<RoutineRecord> = fetchRows(
		selectSql + " where r.workspace_id = ? and r.source_scope_id = ? and r.enabled = true and r.cadence = ? order by r.created_at, r.id",
		workspaceId,
		sourceScopeId,
		cadence.name,
	).map { it.toRoutine() }

	fun hasEnabledReleaseEventRoutines(workspaceId: UUID, sourceScopeId: UUID): Boolean = fetchRows(
		"select exists(select 1 from routines where workspace_id = ? and source_scope_id = ? and enabled = true and cadence in ('ON_GITHUB_RELEASE', 'ON_GIT_TAG'))",
		workspaceId,
		sourceScopeId,
	).firstOrNull()?.get("exists", Boolean::class.java) == true

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
		execute(
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
		val updated = execute(
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
		val updated = execute(
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

	@Transactional
	fun claimNext(workerId: String, now: Instant, staleBefore: Instant): RoutineRecord? = claim(
		workerId = workerId,
		now = now,
		staleBefore = staleBefore,
		where = "(r.enabled = true and r.cadence in ('DAILY', 'WEEKLY') and r.next_run_at <= ?)",
		args = arrayOf(Timestamp.from(now)),
	)

	fun isSourceActive(workspaceId: UUID, sourceScopeId: UUID): Boolean = fetchRows(
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
		workspaceId,
		sourceScopeId,
	).firstOrNull()?.get("exists", Boolean::class.java) == true

	fun lockWorkspaceActivity(workspaceId: UUID) {
		fetchRows(
			"select pg_advisory_xact_lock(hashtextextended(?, 0))",
			"routine-activity:$workspaceId",
		)
	}

	fun finish(
		claim: RoutineRecord,
		now: Instant,
		nextRunAt: Instant,
		status: String,
		artifactWorkflowRunId: UUID? = null,
		errorCode: String? = null,
		activityCursor: RoutineActivityCursor? = null,
	) {
		val executionId = requireNotNull(claim.activeExecutionId) { "Routine claim has no execution identity" }
		val updated = execute(
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
			artifactWorkflowRunId,
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
	): RoutineRecord? {
		val row = fetchRows(
			claimSelectSql + "\n" + """
				where $where
				and (r.claimed_by is null or r.claimed_at < ?)
				order by r.next_run_at, r.id for update skip locked limit 1
			""".trimIndent(),
			*args,
			Timestamp.from(staleBefore),
		).firstOrNull()?.toRoutine() ?: return null
		val claimedAt = Timestamp.from(now)
		val executionId = row.activeExecutionId ?: uuidGenerator.next()
		val updated = execute(
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
		return if (updated != 1) null else find(row.workspaceId, row.id)
	}

	private fun Record.toRoutine(): RoutineRecord = RoutineRecord(
		id = requireNotNull(get("id", UUID::class.java)),
		workspaceId = requireNotNull(get("workspace_id", UUID::class.java)),
		createdByUserId = requireNotNull(get("created_by_user_id", UUID::class.java)),
		sourceScopeId = requireNotNull(get("source_scope_id", UUID::class.java)),
		sourceLabel = get("source_label", String::class.java),
		name = requireNotNull(get("name", String::class.java)),
		instruction = requireNotNull(get("instruction", String::class.java)),
		cadence = RoutineCadence.valueOf(requireNotNull(get("cadence", String::class.java))),
		enabled = requireNotNull(get("enabled", Boolean::class.java)),
		activityCursorSequence = get("activity_cursor_sequence", Long::class.javaObjectType),
		lastRunAt = get("last_run_at", OffsetDateTime::class.java)?.toInstant(),
		nextRunAt = requireNotNull(get("next_run_at", OffsetDateTime::class.java)).toInstant(),
		activeExecutionId = get("active_execution_id", UUID::class.java),
		lastExecutionId = get("last_execution_id", UUID::class.java),
		lastArtifactWorkflowRunId = get("last_generation_run_id", UUID::class.java),
		lastRunStatus = get("effective_run_status", String::class.java),
		lastErrorCode = get("effective_error_code", String::class.java),
		claimedBy = get("claimed_by", String::class.java),
		claimedAt = get("claimed_at", OffsetDateTime::class.java)?.toInstant(),
		transitionVersion = requireNotNull(get("transition_version", Long::class.javaObjectType)),
		createdAt = requireNotNull(get("created_at", OffsetDateTime::class.java)).toInstant(),
		updatedAt = requireNotNull(get("updated_at", OffsetDateTime::class.java)).toInstant(),
	)

	private fun fetchRows(sql: String, vararg bindings: Any?): List<Record> = dsl.fetch(sql, *bindings)

	private fun execute(sql: String, vararg bindings: Any?): Int = dsl.execute(sql, *bindings)

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
