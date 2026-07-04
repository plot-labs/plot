package com.plot.api.writingblock

fun WritingBlock.toResponse(): WritingBlockResponse {
	return WritingBlockResponse(
		id = id,
		sourceOrigin = sourceOrigin,
		sourceKind = sourceKind,
		title = title,
		body = body,
		url = url,
		canonicalUrl = canonicalUrl,
		author = author,
		platform = platform,
		metadata = metadata,
		sourceCreatedAt = sourceCreatedAt,
		sourceUpdatedAt = sourceUpdatedAt,
		ingestedAt = ingestedAt,
		status = status,
		createdAt = createdAt,
		updatedAt = updatedAt,
	)
}
