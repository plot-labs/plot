package com.plot.api.github

import com.plot.api.persistence.SqlExecutor
import com.plot.api.routine.RoutineAgentPersistence
import com.plot.api.routine.RoutinePersistence
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class GitHubReleaseRoutineProjection(
	private val sql: SqlExecutor,
	private val executions: RoutineAgentPersistence,
	private val routines: RoutinePersistence,
) {
	fun finish(requestId: UUID, status: GitHubReleaseDraftStatus, errorCode: String? = null) {
		if (status == GitHubReleaseDraftStatus.READY) return
        if (status == GitHubReleaseDraftStatus.DEFERRED) {
            sql.update("""update routine_executions set status = 'DEFERRED', claimed_by = null, claimed_at = null,
                transition_version = transition_version + 1, finished_at = now(), updated_at = now()
                where release_request_id = ? and status = 'PROBING'""", requestId)
            sql.update("""update routines r set last_run_status = 'DEFERRED', last_error_code = null, updated_at = now()
                from routine_executions e where e.release_request_id = ? and r.workspace_id = e.workspace_id
                and r.id = e.routine_id and r.active_execution_id = e.id""", requestId)
            return
        }
		val pending = sql.query(
			"select workspace_id, id from routine_executions where release_request_id = ? and status = 'PROBING'",
			{ row, _ -> row.getObject("workspace_id", UUID::class.java)!! to row.getObject("id", UUID::class.java)!! },
			requestId,
		)
		val now = Instant.now()
		pending.forEach { (workspaceId, id) ->
			val code = errorCode ?: if (status == GitHubReleaseDraftStatus.NEEDS_RANGE) "GITHUB_RELEASE_RANGE_REQUIRED" else "RELEASE_PROCESSING_FAILED"
			val execution = if (status == GitHubReleaseDraftStatus.NO_ACTIVITY) {
				executions.markNoActivity(workspaceId, id, now)
			} else {
				executions.failExecution(workspaceId, id, code, now)
			}
			val routine = routines.find(workspaceId, execution.routineId) ?: return@forEach
			executions.projectRoutine(
				workspaceId, routine.id, execution.id, now, routine.nextRunAt,
				if (status == GitHubReleaseDraftStatus.NO_ACTIVITY) "NO_ACTIVITY" else "FAILED",
				if (status == GitHubReleaseDraftStatus.NO_ACTIVITY) null else code,
				execution.createdAt,
			)
		}
	}
}
