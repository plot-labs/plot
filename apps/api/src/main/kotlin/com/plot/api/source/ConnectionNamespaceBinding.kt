package com.plot.api.source

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "connection_namespace_bindings")
class ConnectionNamespaceBinding(
	@Id var id: UUID,
	var workspaceId: UUID,
	var provider: String,
	var connectionId: UUID,
	var sourceNamespaceId: UUID,
	var externalPrincipalId: String?,
	@JdbcTypeCode(SqlTypes.JSON) var capabilities: Map<String, Any?>?,
	var status: String,
	var validFrom: Instant,
	var validTo: Instant?,
	var createdAt: Instant,
	var updatedAt: Instant,
)
