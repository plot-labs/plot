package com.plot.api.source

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "source_imports")
class SourceImport(
	@Id var id: UUID,
	var workspaceId: UUID,
	var sourceScopeId: UUID,
	var observationId: UUID,
	var fromInstant: Instant,
	var toInstant: Instant,
	var status: String,
	var eligibleCount: Int,
	var blockCreatedCount: Int,
	var blockUpdatedCount: Int,
	var blockUnchangedCount: Int,
	var errorCode: String?,
	var errorMessage: String?,
	var startedAt: Instant,
	var completedAt: Instant?,
	var createdAt: Instant,
)
