package com.plot.api.worksession

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal object WorkSessionTable : Table("work_sessions") {
	val id = javaUUID("id")
	val workspaceId = javaUUID("workspace_id")
	val title = text("title").nullable()
	val status = varchar("status", 32)
	val createdByUserId = javaUUID("created_by_user_id").nullable()
	val lastActivityAt = timestampWithTimeZone("last_activity_at").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val updatedAt = timestampWithTimeZone("updated_at")
	val latestArtifactWorkflowRunId = javaUUID("latest_generation_run_id").nullable()
	val sessionKind = varchar("session_kind", 32)

	override val primaryKey = PrimaryKey(id)
}
