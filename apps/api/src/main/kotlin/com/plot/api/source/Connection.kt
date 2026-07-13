package com.plot.api.source

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "connections")
class Connection(
	@Id var id: UUID,
	var workspaceId: UUID,
	var provider: String,
	var connectionKind: String,
	var externalConnectionKey: String,
	var externalAccountLogin: String?,
	@JdbcTypeCode(SqlTypes.JSON) var permissions: Map<String, Any?>?,
	var status: String,
	var createdByUserId: UUID?,
	var createdAt: Instant,
	var updatedAt: Instant,
)
