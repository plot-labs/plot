package com.plot.api.github

import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class GitHubImportEligibilityTest {
	@Test
	fun usesHalfOpenMergedAtWindowAndDeduplicatesByExternalId() {
		val from = Instant.parse("2026-01-01T00:00:00Z")
		val to = Instant.parse("2026-02-01T00:00:00Z")
		val base = pullRequest(1, Instant.parse("2026-01-10T00:00:00Z"))
		val duplicate = base.copy(title = "stale duplicate")
		val atUpperBound = pullRequest(2, to)
		val unmerged = pullRequest(3, null)
		val beforeLowerBound = pullRequest(4, Instant.parse("2025-12-31T23:59:59Z"))

		val result = GitHubImportEligibility.select(listOf(atUpperBound, base, duplicate, unmerged, beforeLowerBound), from, to)

		assertEquals(listOf(1L), result.map { it.id })
		assertEquals("Ship 1", result.single().title)
	}

	private fun pullRequest(id: Long, mergedAt: Instant?): GitHubPullRequest = GitHubPullRequest(
		id = id,
		number = id.toInt(),
		title = "Ship $id",
		body = null,
		author = null,
		url = "https://github.com/acme/plot/pull/$id",
		baseBranch = "main",
		headBranch = "feature-$id",
		createdAt = Instant.parse("2026-01-01T00:00:00Z"),
		updatedAt = Instant.parse("2026-01-02T00:00:00Z"),
		mergedAt = mergedAt,
	)
}
