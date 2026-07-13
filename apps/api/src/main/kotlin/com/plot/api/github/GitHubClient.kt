package com.plot.api.github

interface GitHubClient {
	fun listInstallationRepositories(installationId: Long): List<GitHubRepository>

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
}
