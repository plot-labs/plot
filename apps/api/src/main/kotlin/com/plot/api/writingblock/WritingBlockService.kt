package com.plot.api.writingblock

import com.plot.api.common.ApiException
import com.plot.api.common.UuidGenerator
import com.plot.api.dev.DevContext
import com.plot.api.source.SourceManagedAccessGuard
import com.plot.api.source.SourceScopeRepository
import com.plot.api.writingblock.dto.CreateWritingBlockRequest
import com.plot.api.writingblock.dto.UpdateWritingBlockRequest
import com.plot.api.writingblock.dto.WritingBlockResponse
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

data class WritingBlockPageResponse(
	val items: List<WritingBlockResponse>,
	val page: Int,
	val size: Int,
	val totalItems: Long,
	val totalPages: Int,
)

@Service
class WritingBlockService(
	private val devContext: DevContext,
	private val uuidGenerator: UuidGenerator,
	private val writingBlockRepository: WritingBlockRepository,
	private val sourceScopeRepository: SourceScopeRepository,
	private val sourceManagedAccessGuard: SourceManagedAccessGuard,
) {

	@Transactional(readOnly = true)
	fun list(sourceScopeId: UUID? = null, page: Int = 0, size: Int = 50): WritingBlockPageResponse {
		if (page < 0 || size !in 1..100) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Page must be non-negative and size must be between 1 and 100")
		if (sourceScopeId != null && sourceScopeRepository.findByWorkspaceIdAndId(devContext.devWorkspaceId, sourceScopeId) == null) {
			throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Source scope not found")
		}
		if (sourceScopeId != null) sourceManagedAccessGuard.requireReadable()
		val pageable = PageRequest.of(page, size, Sort.by(
			Sort.Order.desc("sourceCreatedAt").nullsLast(), Sort.Order.desc("externalObjectKey").nullsLast(),
			Sort.Order.desc("createdAt"), Sort.Order.desc("id"),
		))
		val blocks = sourceScopeId?.let {
			writingBlockRepository.findAllByWorkspaceIdAndSourceScopeId(
				devContext.devWorkspaceId,
				it,
				pageable,
			)
		} ?: writingBlockRepository.findAllByWorkspaceId(devContext.devWorkspaceId, pageable)
		if (blocks.content.any { it.sourceNamespaceId != null }) sourceManagedAccessGuard.requireReadable()
		return WritingBlockPageResponse(blocks.content.map { it.toResponse() }, page, size, blocks.totalElements, blocks.totalPages)
	}

	@Transactional
	fun create(request: CreateWritingBlockRequest): WritingBlockResponse {
		val fields = request.toFields()
		requireTitleOrBody(fields)

		val now = Instant.now()
		val writingBlock = WritingBlock(
			id = uuidGenerator.next(),
			workspaceId = devContext.devWorkspaceId,
			sourceNamespaceId = null,
			externalObjectKey = null,
			sourceOrigin = fields.sourceOrigin,
			sourceKind = fields.sourceKind,
			title = fields.title,
			body = fields.body,
			url = fields.url,
			canonicalUrl = fields.canonicalUrl,
			author = fields.author,
			platform = fields.platform,
			metadata = fields.metadata,
			contentHash = contentHash(fields.title, fields.body),
			sourceCreatedAt = fields.sourceCreatedAt,
			sourceUpdatedAt = fields.sourceUpdatedAt,
			ingestedAt = now,
			status = "ACTIVE",
			createdByUserId = devContext.devUserId,
			createdAt = now,
			updatedAt = now,
		)

		return writingBlockRepository.save(writingBlock).toResponse()
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): WritingBlockResponse {
		val block = requireBlock(id)
		if (block.sourceNamespaceId != null) sourceManagedAccessGuard.requireReadable()
		return block.toResponse()
	}

	@Transactional
	fun update(id: UUID, request: UpdateWritingBlockRequest): WritingBlockResponse {
		val writingBlock = requireBlock(id)
		if (writingBlock.sourceNamespaceId != null) {
			throw ApiException(
				HttpStatus.CONFLICT,
				"SOURCE_MANAGED",
				"Source-managed writing blocks cannot be edited directly",
			)
		}
		val fields = request.toFields()
		requireTitleOrBody(fields)

		writingBlock.sourceOrigin = fields.sourceOrigin
		writingBlock.sourceKind = fields.sourceKind
		writingBlock.title = fields.title
		writingBlock.body = fields.body
		writingBlock.url = fields.url
		writingBlock.canonicalUrl = fields.canonicalUrl
		writingBlock.author = fields.author
		writingBlock.platform = fields.platform
		writingBlock.metadata = fields.metadata
		writingBlock.contentHash = contentHash(fields.title, fields.body)
		writingBlock.sourceCreatedAt = fields.sourceCreatedAt
		writingBlock.sourceUpdatedAt = fields.sourceUpdatedAt
		writingBlock.updatedAt = Instant.now()

		return writingBlock.toResponse()
	}

	private fun requireBlock(id: UUID): WritingBlock {
		return writingBlockRepository.findByWorkspaceIdAndId(devContext.devWorkspaceId, id)
			?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Writing block not found")
	}

	private fun requireTitleOrBody(fields: WritingBlockFields) {
		if (fields.title.isNullOrBlank() && fields.body.isNullOrBlank()) {
			throw ApiException(
				HttpStatus.BAD_REQUEST,
				"BAD_REQUEST",
				"Writing block requires title or body",
			)
		}
	}

	private fun contentHash(title: String?, body: String?): String {
		val digest = MessageDigest.getInstance("SHA-256")
			.digest("${title.orEmpty()}\n${body.orEmpty()}".toByteArray(Charsets.UTF_8))
		return HexFormat.of().formatHex(digest)
	}
}

private data class WritingBlockFields(
	val sourceOrigin: String,
	val sourceKind: String,
	val title: String?,
	val body: String?,
	val url: String?,
	val canonicalUrl: String?,
	val author: String?,
	val platform: String?,
	val metadata: Map<String, Any?>?,
	val sourceCreatedAt: Instant?,
	val sourceUpdatedAt: Instant?,
)

private fun CreateWritingBlockRequest.toFields(): WritingBlockFields {
	return WritingBlockFields(
		sourceOrigin = sourceOrigin.trim(),
		sourceKind = sourceKind.trim(),
		title = title?.trim(),
		body = body?.trim(),
		url = url?.trim(),
		canonicalUrl = canonicalUrl?.trim(),
		author = author?.trim(),
		platform = platform?.trim(),
		metadata = metadata,
		sourceCreatedAt = sourceCreatedAt,
		sourceUpdatedAt = sourceUpdatedAt,
	)
}

private fun UpdateWritingBlockRequest.toFields(): WritingBlockFields {
	return WritingBlockFields(
		sourceOrigin = sourceOrigin.trim(),
		sourceKind = sourceKind.trim(),
		title = title?.trim(),
		body = body?.trim(),
		url = url?.trim(),
		canonicalUrl = canonicalUrl?.trim(),
		author = author?.trim(),
		platform = platform?.trim(),
		metadata = metadata,
		sourceCreatedAt = sourceCreatedAt,
		sourceUpdatedAt = sourceUpdatedAt,
	)
}
