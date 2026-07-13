package com.plot.api.github

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubWritingBlockTransformerTest {
	@Test
	fun transformsPullRequestDirectlyIntoProviderNeutralWritingBlock() {
		val namespaceId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		val observationId = UUID.randomUUID()
		val pullRequest = GitHubPullRequest(
			id = 987,
			number = 42,
			title = "  Ship source adapters  ",
			body = "  GitHub first  ",
			author = "ada",
			url = "https://github.com/acme/app/pull/42",
			baseBranch = "main",
			headBranch = "feature/adapters",
			createdAt = Instant.parse("2026-07-01T00:00:00Z"),
			updatedAt = Instant.parse("2026-07-02T00:00:00Z"),
			mergedAt = Instant.parse("2026-07-02T00:00:00Z"),
		)

		val result = GitHubWritingBlockTransformer().transform(namespaceId, scopeId, observationId, pullRequest)

		assertEquals(namespaceId, result.sourceNamespaceId)
		assertEquals(scopeId, result.sourceScopeId)
		assertEquals(observationId, result.observationId)
		assertEquals("987", result.externalObjectKey)
		assertEquals("pull_request", result.sourceKind)
		assertEquals("github", result.platform)
		assertEquals("Ship source adapters", result.title)
		assertEquals("GitHub first", result.body)
		assertEquals(42, result.metadata["number"])
	}
}
