package com.plot.api.source

import java.time.Instant
import java.util.UUID

class SourceScope(
	var id: UUID,
	var workspaceId: UUID,
	var sourceNamespaceId: UUID,
	var provider: String,
	var scopeSemantics: String,
	var scopeKind: String,
	var externalScopeKey: String,
	var externalKey: String?,
	var displayName: String,
	var url: String?,
	var metadata: Map<String, Any?>?,
	var status: String,
	var createdAt: Instant,
	var updatedAt: Instant,
)
