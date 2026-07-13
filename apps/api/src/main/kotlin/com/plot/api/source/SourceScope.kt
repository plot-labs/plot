package com.plot.api.source

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "source_scopes")
class SourceScope(
	@Id var id: UUID,
	var workspaceId: UUID,
	var sourceNamespaceId: UUID,
	var provider: String,
	var scopeSemantics: String,
	var scopeKind: String,
	var externalScopeKey: String,
	var externalKey: String?,
	var displayName: String,
	var url: String?,
	@JdbcTypeCode(SqlTypes.JSON) var metadata: Map<String, Any?>?,
	var status: String,
	var createdAt: Instant,
	var updatedAt: Instant,
)
