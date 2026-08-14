package com.plot.api.worksession

import java.time.Instant
import java.util.UUID

class WorkSession(
	var id: UUID,
	var workspaceId: UUID,
	var title: String?,
	var status: String,
	var createdByUserId: UUID?,
	var latestArtifactWorkflowRunId: UUID?,
	var lastActivityAt: Instant?,
	var createdAt: Instant,
	var updatedAt: Instant,
)
