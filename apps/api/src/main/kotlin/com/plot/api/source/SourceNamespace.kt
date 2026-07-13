package com.plot.api.source

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "source_namespaces")
class SourceNamespace(
	@Id var id: UUID,
	var workspaceId: UUID,
	var provider: String,
	var namespaceKind: String,
	var externalNamespaceKey: String,
	var displayName: String?,
	var status: String,
	var createdAt: Instant,
	var updatedAt: Instant,
)
