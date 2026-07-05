package com.plot.api.task

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface TaskRepository : JpaRepository<Task, UUID> {
	fun findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId: UUID): List<Task>
	fun findByWorkspaceIdAndId(workspaceId: UUID, id: UUID): Task?
}
