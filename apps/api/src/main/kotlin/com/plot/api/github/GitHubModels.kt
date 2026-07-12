package com.plot.api.github

import java.time.Instant

data class GitHubRepository(
	val id: Long,
	val owner: String,
	val name: String,
	val url: String,
	val defaultBranch: String?,
	val ownerId: Long? = null,
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

data class GitHubHttpResponse(
	val status: Int,
	val headers: Map<String, List<String>>,
	val body: String,
)

fun interface GitHubHttpTransport {
	fun execute(method: String, uri: java.net.URI, headers: Map<String, String>, body: String?): GitHubHttpResponse
}
