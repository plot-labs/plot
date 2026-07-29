package com.plot.api.writingblock

import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.source.SourceManagedAccessGuard
import com.plot.api.source.SourceScopeRepository
import com.plot.api.writingblock.dto.WritingBlockResponse
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

}
