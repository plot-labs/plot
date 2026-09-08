package com.plot.api.content

import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

data class ContentSourceSnapshot(
	val id: UUID,
	val workspaceId: UUID,
	val sourceBundleHash: String,
	val sourceScopeId: UUID?,
	val baseRef: String?,
	val headRef: String?,
	val capturedAt: Instant,
	val briefSnapshotJson: String?,
	val inputs: List<ContentSourceSnapshotInput>,
	val createdAt: Instant,
)

data class ContentSourceSnapshotInput(
	val writingBlockId: UUID?,
	val sourceScopeId: UUID?,
	val sourceProvider: String,
	val sourceKind: String,
	val sourceLabel: String,
	val orderIndex: Int,
	val snapshotTitle: String?,
	val snapshotBody: String,
	val snapshotExcerpt: String?,
	val originalUrl: String?,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
	val contentHash: String,
	val capturedAt: Instant,
)

fun computeSourceBundleHash(
	inputs: List<ContentSourceSnapshotInput>,
	baseRef: String?,
	headRef: String?,
	briefSnapshotJson: String?,
): String {
	val canonical = buildString {
		append(baseRef.orEmpty()).append('|').append(headRef.orEmpty()).append('\n')
		append(briefSnapshotJson?.trim().orEmpty()).append('\n')
		inputs.sortedWith(compareBy(ContentSourceSnapshotInput::orderIndex, { it.writingBlockId ?: UUID(0, 0) }, ContentSourceSnapshotInput::contentHash))
			.forEach { input ->
				append(input.orderIndex).append('|')
				append(input.writingBlockId).append('|')
				append(input.sourceScopeId).append('|')
				append(input.sourceProvider).append('|')
				append(input.sourceKind).append('|')
				append(input.contentHash).append('\n')
			}
	}
	return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
}
