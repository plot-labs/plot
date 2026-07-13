package com.plot.api.github

import com.plot.api.source.ImportedWritingBlock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class GitHubWritingBlockTransformer {
	fun transform(
		sourceNamespaceId: UUID,
		sourceScopeId: UUID,
		observationId: UUID,
		pullRequest: GitHubPullRequest,
	): ImportedWritingBlock {
		val title = pullRequest.title.trim()
		val body = pullRequest.body?.trim()
		check(title.isNotBlank() || !body.isNullOrBlank()) { "GitHub pull request has no usable content" }
		return ImportedWritingBlock(
			sourceNamespaceId = sourceNamespaceId,
			sourceScopeId = sourceScopeId,
			observationId = observationId,
			externalObjectKey = pullRequest.id.toString(),
			sourceOrigin = "integration",
			sourceKind = "pull_request",
			title = title,
			body = body,
			url = pullRequest.url,
			canonicalUrl = pullRequest.url,
			author = pullRequest.author,
			platform = "github",
			metadata = mapOf(
				"number" to pullRequest.number,
				"baseBranch" to pullRequest.baseBranch,
				"headBranch" to pullRequest.headBranch,
				"mergedAt" to pullRequest.mergedAt?.toString(),
			),
			sourceCreatedAt = pullRequest.createdAt,
			sourceUpdatedAt = pullRequest.updatedAt,
		)
	}
}
