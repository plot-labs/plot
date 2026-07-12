package com.plot.api.source

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "writing_block_scopes")
class WritingBlockScope(
	@Id var id: UUID,
	var workspaceId: UUID,
	var sourceNamespaceId: UUID,
	var writingBlockId: UUID,
	var sourceScopeId: UUID,
	var membershipKind: String,
	var status: String,
	var firstSeenAt: Instant,
	var lastSeenAt: Instant,
	var lastObservationId: UUID?,
)
