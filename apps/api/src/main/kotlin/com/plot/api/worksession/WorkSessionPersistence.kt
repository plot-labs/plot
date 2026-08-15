package com.plot.api.worksession

import com.plot.api.persistence.generated.tables.WorkSessions.Companion.WORK_SESSIONS
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.springframework.stereotype.Repository

@Repository
class WorkSessionPersistence(
	private val dsl: DSLContext,
) {
	private val workSessionFields: Array<Field<*>> = arrayOf(
		WORK_SESSIONS.ID,
		WORK_SESSIONS.WORKSPACE_ID,
		WORK_SESSIONS.TITLE,
		WORK_SESSIONS.STATUS,
		WORK_SESSIONS.CREATED_BY_USER_ID,
		WORK_SESSIONS.LAST_ACTIVITY_AT,
		WORK_SESSIONS.CREATED_AT,
		WORK_SESSIONS.UPDATED_AT,
		WORK_SESSIONS.LATEST_GENERATION_RUN_ID,
	)

	fun findRecentByWorkspaceId(workspaceId: UUID): List<WorkSession> {
		return dsl
			.select(*workSessionFields)
			.from(WORK_SESSIONS)
			.where(WORK_SESSIONS.WORKSPACE_ID.eq(workspaceId))
			.orderBy(
				WORK_SESSIONS.LAST_ACTIVITY_AT.coalesce(WORK_SESSIONS.CREATED_AT).desc(),
				WORK_SESSIONS.CREATED_AT.desc(),
			)
			.fetch()
			.map { it.toModel() }
	}

	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WorkSession? {
		return dsl
			.select(*workSessionFields)
			.from(WORK_SESSIONS)
			.where(
				WORK_SESSIONS.WORKSPACE_ID.eq(workspaceId),
				WORK_SESSIONS.ID.eq(id),
			)
			.fetchOne()
			?.toModel()
	}

	fun insert(workSession: WorkSession): WorkSession {
		return dsl
			.insertInto(WORK_SESSIONS)
			.set(WORK_SESSIONS.ID, workSession.id)
			.set(WORK_SESSIONS.WORKSPACE_ID, workSession.workspaceId)
			.set(WORK_SESSIONS.TITLE, workSession.title)
			.set(WORK_SESSIONS.STATUS, workSession.status)
			.set(WORK_SESSIONS.CREATED_BY_USER_ID, workSession.createdByUserId)
			.set(WORK_SESSIONS.LATEST_GENERATION_RUN_ID, workSession.latestArtifactWorkflowRunId)
			.set(WORK_SESSIONS.LAST_ACTIVITY_AT, workSession.lastActivityAt?.toOffsetDateTime())
			.set(WORK_SESSIONS.CREATED_AT, workSession.createdAt.toOffsetDateTime())
			.set(WORK_SESSIONS.UPDATED_AT, workSession.updatedAt.toOffsetDateTime())
			.returning(*workSessionFields)
			.fetchOne()
			?.toModel()
			?: error("Work session insert did not return a row")
	}

	fun update(
		workspaceId: UUID,
		id: UUID,
		title: String?,
		now: Instant,
	): WorkSession? {
		val update = dsl
			.update(WORK_SESSIONS)
			.set(WORK_SESSIONS.LAST_ACTIVITY_AT, now.toOffsetDateTime())
			.set(WORK_SESSIONS.UPDATED_AT, now.toOffsetDateTime())

		if (title != null) {
			update.set(WORK_SESSIONS.TITLE, title)
		}

		return update
			.where(
				WORK_SESSIONS.WORKSPACE_ID.eq(workspaceId),
				WORK_SESSIONS.ID.eq(id),
			)
			.returning(*workSessionFields)
			.fetchOne()
			?.toModel()
	}

	private fun Record.toModel(): WorkSession {
		return WorkSession(
			id = requireNotNull(get(WORK_SESSIONS.ID)),
			workspaceId = requireNotNull(get(WORK_SESSIONS.WORKSPACE_ID)),
			title = get(WORK_SESSIONS.TITLE),
			status = requireNotNull(get(WORK_SESSIONS.STATUS)),
			createdByUserId = get(WORK_SESSIONS.CREATED_BY_USER_ID),
			latestArtifactWorkflowRunId = get(WORK_SESSIONS.LATEST_GENERATION_RUN_ID),
			lastActivityAt = get(WORK_SESSIONS.LAST_ACTIVITY_AT)?.toInstant(),
			createdAt = requireNotNull(get(WORK_SESSIONS.CREATED_AT)).toInstant(),
			updatedAt = requireNotNull(get(WORK_SESSIONS.UPDATED_AT)).toInstant(),
		)
	}

	private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
