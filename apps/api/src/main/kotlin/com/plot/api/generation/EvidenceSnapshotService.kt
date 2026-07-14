package com.plot.api.generation

import com.plot.api.common.UuidGenerator
import com.plot.api.generation.model.EvidenceSnapshot
import com.plot.api.generation.model.SourceProvider
import com.plot.api.writingblock.WritingBlock
import com.plot.api.writingblock.writingBlockContentHash
import java.net.URI
import java.time.Clock
import java.util.UUID

class InvalidEvidenceSnapshotException(message: String) : IllegalArgumentException(message)

class EvidenceSnapshotService(
	private val idGenerator: () -> UUID = UuidGenerator()::next,
	private val clock: Clock = Clock.systemUTC(),
) {
	fun snapshot(generationRunId: UUID, orderIndex: Int, writingBlock: WritingBlock): EvidenceSnapshot {
		if (orderIndex < 0) invalid("Evidence order must be non-negative")
		val title = writingBlock.title?.trim()?.takeIf { it.isNotBlank() }
		val body = writingBlock.body?.trim()?.takeIf { it.isNotBlank() }
		val snapshotBody = body ?: title ?: invalid("Evidence requires a title or body")
		val provider = parseProvider(writingBlock.platform)
		val sourceKind = writingBlock.sourceKind.trim().takeIf { it.isNotBlank() }
			?: invalid("Evidence source kind is blank")
		val sourceLabel = title ?: "${provider.displayName} $sourceKind"
		val originalUrl = canonicalHttpUrl(
			writingBlock.canonicalUrl?.takeIf { it.isNotBlank() } ?: writingBlock.url,
		)
		val contentHash = writingBlock.contentHash?.trim()?.takeIf { it.isNotBlank() }
			?: writingBlockContentHash(title, body)

		return EvidenceSnapshot(
			id = idGenerator(),
			generationRunId = generationRunId,
			writingBlockId = writingBlock.id,
			orderIndex = orderIndex,
			sourceProvider = provider,
			sourceKind = sourceKind,
			sourceLabel = sourceLabel,
			snapshotTitle = title,
			snapshotBody = snapshotBody,
			snapshotExcerpt = body ?: title,
			originalUrl = originalUrl,
			sourceCreatedAt = writingBlock.sourceCreatedAt,
			sourceUpdatedAt = writingBlock.sourceUpdatedAt,
			contentHash = contentHash,
			capturedAt = clock.instant(),
		)
	}

	private fun parseProvider(platform: String?): SourceProvider = when (platform?.trim()?.lowercase()) {
		"github" -> SourceProvider.GITHUB
		"slack" -> SourceProvider.SLACK
		"linear" -> SourceProvider.LINEAR
		else -> invalid("Unsupported evidence provider")
	}

	private fun canonicalHttpUrl(value: String?): String {
		val url = value?.trim()?.takeIf { it.isNotBlank() } ?: invalid("Evidence original URL is blank")
		val uri = try {
			URI(url)
		} catch (_: Exception) {
			invalid("Evidence original URL is invalid")
		}
		if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
			invalid("Evidence original URL must use HTTP or HTTPS")
		}
		return uri.toASCIIString()
	}

	private fun invalid(message: String): Nothing = throw InvalidEvidenceSnapshotException(message)
}

private val SourceProvider.displayName: String
	get() = name.lowercase().replaceFirstChar { it.uppercase() }
