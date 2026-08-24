package com.plot.api.github

interface GitHubClient {
	fun listInstallationRepositories(installationId: Long): List<GitHubRepository>

	/** Fetches installation metadata, including the owning account, with app-level credentials. */
	fun getInstallation(installationId: Long): GitHubInstallation =
		throw UnsupportedOperationException("GitHub installation lookup is not implemented")

	/** Resolves the GitHub identity behind a stored user access token. */
	fun resolveAuthenticatedUser(userAccessToken: String): GitHubUserIdentity =
		throw UnsupportedOperationException("GitHub user lookup is not implemented")

	/** Returns the user's role in an organization, or null when they are not a member. */
	fun organizationMembershipRole(userAccessToken: String, org: String, username: String): String? =
		throw UnsupportedOperationException("GitHub organization membership lookup is not implemented")

	/** Verifies a grant with a repository-scoped installation token. */
	fun verifyRepositoryAccess(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
	): GitHubRepository = listInstallationRepositories(installationId).firstOrNull { it.id == repositoryId }
		?: throw com.plot.api.common.ApiException(
			org.springframework.http.HttpStatus.NOT_FOUND,
			"NOT_FOUND",
			"GitHub repository is not granted to this installation",
		)

	fun listClosedPullRequests(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		pageCap: Int,
	): List<GitHubPullRequest>

	fun listClosedPullRequestsPage(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		continuation: String?,
	): GitHubPullRequestPage = if (continuation == null) {
		GitHubPullRequestPage(
			listClosedPullRequests(installationId, repositoryId, owner, repository, pageCap = 1),
			nextPage = null,
		)
	} else {
		throw UnsupportedOperationException("GitHub pull-request pagination is not implemented")
	}

	fun listPublishedReleaseTags(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		limit: Int,
	): GitHubTagPage = throw UnsupportedOperationException("GitHub release listing is not implemented")

	fun listRepositoryTags(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		limit: Int,
	): GitHubTagPage = throw UnsupportedOperationException("GitHub tag listing is not implemented")

	fun resolveTagCommit(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		tagName: String,
	): String = throw UnsupportedOperationException("Git tag resolution is not implemented")

	fun compareCommits(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		baseSha: String,
		headSha: String,
		pageCap: Int,
	): GitHubCompareResult = throw UnsupportedOperationException("Git commit comparison is not implemented")

	fun listPullRequestsForCommit(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		commitSha: String,
	): List<GitHubPullRequest> = throw UnsupportedOperationException("Commit pull-request lookup is not implemented")
}
