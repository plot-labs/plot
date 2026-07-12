package com.plot.api.writingblock

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "writing_blocks")
class WritingBlock(
	@Id
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
	@JdbcTypeCode(SqlTypes.JSON)
	var metadata: Map<String, Any?>?,
	var contentHash: String?,
	var sourceCreatedAt: Instant?,
	var sourceUpdatedAt: Instant?,
	var ingestedAt: Instant,
	var status: String,
	var createdByUserId: UUID?,
	var createdAt: Instant,
	var updatedAt: Instant,
)
