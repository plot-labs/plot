package com.plot.api.github

import org.springframework.stereotype.Component

data class GitHubReleaseRange(
	val baseSha: String,
	val headSha: String,
	val boundaryReason: String,
	val comparison: GitHubCompareResult,
)

sealed interface GitHubReleaseRangeResult {
	data class Resolved(val range: GitHubReleaseRange) : GitHubReleaseRangeResult
	data class NeedsRange(val headSha: String) : GitHubReleaseRangeResult
	data class NoActivity(
		val baseSha: String,
		val headSha: String,
		val boundaryReason: String,
	) : GitHubReleaseRangeResult
}

interface GitHubReleaseRangeResolver {
	fun resolve(
		context: GitHubReleaseSourceContext,
		request: GitHubReleaseDraftRequest,
	): GitHubReleaseRangeResult
}

@Component
class DefaultGitHubReleaseRangeResolver(
	private val client: GitHubClient,
	private val requestPersistence: GitHubReleaseRequestStore,
	private val properties: GitHubProperties,
) : GitHubReleaseRangeResolver {
	override fun resolve(
		context: GitHubReleaseSourceContext,
		request: GitHubReleaseDraftRequest,
	): GitHubReleaseRangeResult {
		val headSha = client.resolveTagCommit(
			context.installationId,
			context.repositoryId,
			context.owner,
			context.repository,
			request.tagName,
		)
		if (request.observedHeadSha != null && request.observedHeadSha != headSha) {
			throw GitHubReleasePermanentException("GITHUB_TAG_MOVED")
		}
		val candidates = requestPersistence.findPreviousBoundaries(
			context.workspaceId,
			context.sourceScopeId,
			request.id,
		)
		for (candidate in candidates) {
			val baseSha = candidate.headSha ?: continue
			val comparison = client.compareCommits(
				context.installationId,
				context.repositoryId,
				context.owner,
				context.repository,
				baseSha,
				headSha,
				properties.comparePageCap,
			)
			when {
				comparison.status == "ahead" && comparison.aheadBy > 0 -> {
					return GitHubReleaseRangeResult.Resolved(
						GitHubReleaseRange(
							baseSha = baseSha,
							headSha = headSha,
							boundaryReason = PREVIOUS_RELEASE_HEAD,
							comparison = comparison,
						),
					)
				}
				comparison.status == "identical" -> {
					return GitHubReleaseRangeResult.NoActivity(
						baseSha,
						headSha,
						PREVIOUS_RELEASE_HEAD,
					)
				}
			}
		}
		requestPersistence.saveHeadAndFinishNeedsRange(
			request.id,
			request.transitionVersion,
			headSha,
		)
		return GitHubReleaseRangeResult.NeedsRange(headSha)
	}

	private companion object {
		const val PREVIOUS_RELEASE_HEAD = "PREVIOUS_RELEASE_HEAD"
	}
}
