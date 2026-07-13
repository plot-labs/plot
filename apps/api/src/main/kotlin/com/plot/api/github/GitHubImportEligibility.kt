package com.plot.api.github

import java.time.Instant

object GitHubImportEligibility {
	fun select(pullRequests: Iterable<GitHubPullRequest>, from: Instant, to: Instant): List<GitHubPullRequest> =
		pullRequests
			.asSequence()
			.distinctBy { it.id }
			.filter { it.mergedAt != null && it.mergedAt >= from && it.mergedAt < to }
			.sortedBy { it.id }
			.toList()
}
