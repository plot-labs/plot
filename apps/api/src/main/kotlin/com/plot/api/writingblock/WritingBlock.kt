package com.plot.api.writingblock

import java.time.Instant
import java.util.UUID

class WritingBlock(
	var id: UUID,
	var workspaceId: UUID,
	var sourceNamespaceId: UUID?,
	var externalObjectKey: String?,
	var sourceOrigin: String,
	var sourceKind: String,
	var title: String?,
	var body: String?,
	var url: String?,
	var canonicalUrl: String?,
	var author: String?,
	var platform: String?,
	var metadata: Map<String, Any?>?,
	var contentHash: String?,
	var sourceCreatedAt: Instant?,
	var sourceUpdatedAt: Instant?,
	var ingestedAt: Instant,
	var status: String,
	var createdByUserId: UUID?,
	var createdAt: Instant,
	var updatedAt: Instant,
	var activitySequence: Long = 0,
)
