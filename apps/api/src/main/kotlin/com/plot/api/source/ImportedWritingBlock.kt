package com.plot.api.source

import java.time.Instant
import java.util.UUID

/** Provider-neutral normalized payload accepted by the Writing Block importer. */
data class ImportedWritingBlock(
	val sourceNamespaceId: UUID,
	val sourceScopeId: UUID,
	val observationId: UUID,
	val externalObjectKey: String,
	val sourceOrigin: String,
	val sourceKind: String,
	val title: String,
	val body: String?,
	val url: String,
	val canonicalUrl: String,
	val author: String?,
	val platform: String,
	val metadata: Map<String, Any?>,
	val sourceCreatedAt: Instant,
	val sourceUpdatedAt: Instant,
)
