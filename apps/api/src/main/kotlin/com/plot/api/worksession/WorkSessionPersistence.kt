package com.plot.api.worksession

import com.plot.api.persistence.ExposedSqlExecutor
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class WorkSessionPersistence(
	private val sql: ExposedSqlExecutor,
) {
	@Transactional(readOnly = true)
	fun findRecentByWorkspaceId(workspaceId: UUID): List<WorkSession> = sql.execute {
		WorkSessionTable
			.selectAll()
			.where {
				(WorkSessionTable.workspaceId eq workspaceId) and
					(WorkSessionTable.sessionKind neq "AUTOMATION")
			}
			.orderBy(
				Coalesce(WorkSessionTable.lastActivityAt, WorkSessionTable.createdAt) to SortOrder.DESC,
				WorkSessionTable.createdAt to SortOrder.DESC,
			)
			.map { it.toModel() }
	}

	@Transactional(readOnly = true)
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): WorkSession? = sql.execute {
		WorkSessionTable
			.selectAll()
			.where {
				(WorkSessionTable.workspaceId eq workspaceId) and (WorkSessionTable.id eq id)
			}
			.singleOrNull()
			?.toModel()
	}

	@Transactional
	fun insert(workSession: WorkSession): WorkSession = sql.execute {
		WorkSessionTable.insert {
			it[id] = workSession.id
			it[workspaceId] = workSession.workspaceId
			it[title] = workSession.title
			it[status] = workSession.status
			it[createdByUserId] = workSession.createdByUserId
			it[latestArtifactWorkflowRunId] = workSession.latestArtifactWorkflowRunId
			it[sessionKind] = "CHAT"
			it[lastActivityAt] = workSession.lastActivityAt?.atOffset(ZoneOffset.UTC)
			it[createdAt] = workSession.createdAt.atOffset(ZoneOffset.UTC)
			it[updatedAt] = workSession.updatedAt.atOffset(ZoneOffset.UTC)
		}
		workSession
	}

	@Transactional
	fun update(
		workspaceId: UUID,
		id: UUID,
		title: String?,
		now: Instant,
	): WorkSession? = sql.execute {
		val updated = WorkSessionTable.update({
			(WorkSessionTable.workspaceId eq workspaceId) and (WorkSessionTable.id eq id)
		}) {
			it[lastActivityAt] = now.atOffset(ZoneOffset.UTC)
			it[updatedAt] = now.atOffset(ZoneOffset.UTC)
			if (title != null) {
				it[WorkSessionTable.title] = title
			}
		}
		if (updated == 1) findByWorkspaceIdAndId(workspaceId, id) else null
	}

	private fun ResultRow.toModel(): WorkSession {
		return WorkSession(
			id = this[WorkSessionTable.id],
			workspaceId = this[WorkSessionTable.workspaceId],
			title = this[WorkSessionTable.title],
			status = this[WorkSessionTable.status],
			createdByUserId = this[WorkSessionTable.createdByUserId],
			latestArtifactWorkflowRunId = this[WorkSessionTable.latestArtifactWorkflowRunId],
			lastActivityAt = this[WorkSessionTable.lastActivityAt]?.toInstant(),
			createdAt = this[WorkSessionTable.createdAt].toInstant(),
			updatedAt = this[WorkSessionTable.updatedAt].toInstant(),
		)
	}
}
