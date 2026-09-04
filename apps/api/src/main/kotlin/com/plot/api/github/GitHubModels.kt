package com.plot.api.github

import java.time.Instant

data class GitHubRepository(
	val id: Long,
	val owner: String,
	val name: String,
	val url: String,
	val defaultBranch: String?,
	val ownerId: Long? = null,
	val visibility: String = "PUBLIC",
)

data class GitHubPullRequest(
	val id: Long,
	val number: Int,
	val title: String,
	val body: String?,
	val author: String?,
	val url: String,
	val baseBranch: String?,
	val headBranch: String?,
	val createdAt: Instant,
	val updatedAt: Instant,
	val mergedAt: Instant?,
)

data class GitHubPullRequestPage(
	val pullRequests: List<GitHubPullRequest>,
	val nextPage: String?,
)

data class GitHubCommit(
	val sha: String,
	val message: String,
	val author: String?,
	val committedAt: Instant?,
	val url: String,
)

data class GitHubChangedFile(
	val filename: String,
	val previousFilename: String?,
	val status: String,
	val additions: Int,
	val deletions: Int,
	val patch: String?,
)

data class GitHubCompareResult(
	val status: String,
	val aheadBy: Int,
	val commits: List<GitHubCommit>,
	val files: List<GitHubChangedFile>,
	val filesTruncated: Boolean,
)

data class GitHubInstallationAccount(
	val id: Long,
	val login: String,
	val type: String,
)

data class GitHubInstallation(
	val id: Long,
	val account: GitHubInstallationAccount,
)

data class GitHubUserIdentity(
	val id: Long,
	val login: String,
)

data class GitHubUserInstallation(
	val installationId: Long,
	val appId: String,
	val accountId: Long,
	val accountLogin: String,
	val accountType: String,
)

data class GitHubHttpResponse(
	val status: Int,
	val headers: Map<String, List<String>>,
	val body: String,
)

fun interface GitHubHttpTransport {
	fun execute(method: String, uri: java.net.URI, headers: Map<String, String>, body: String?): GitHubHttpResponse
}
