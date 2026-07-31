package com.plot.api.github

import com.plot.api.common.ApiException
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class JavaGitHubHttpTransport(private val properties: GitHubProperties) : GitHubHttpTransport {
	private val client: HttpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.build()

	override fun execute(
		method: String,
		uri: URI,
		headers: Map<String, String>,
		body: String?,
	): GitHubHttpResponse {
		val response = try {
			client.send(buildRequest(method, uri, headers, body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw exception
		}
		return GitHubHttpResponse(response.statusCode(), response.headers().map(), response.body())
	}

	internal fun buildRequest(
		method: String,
		uri: URI,
		headers: Map<String, String>,
		body: String?,
	): HttpRequest {
		val builder = HttpRequest.newBuilder(uri)
			.timeout(properties.httpRequestTimeout)
			.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
		headers.forEach { (name, value) -> builder.header(name, value) }
		return builder.build()
	}
}

@Component
class GitHubRestClient(
	private val properties: GitHubProperties,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
	private val transport: GitHubHttpTransport = JavaGitHubHttpTransport(properties),
) : GitHubClient {

	override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> {
		val token = installationToken(installationId)
		val repositories = mutableListOf<GitHubRepository>()
		var next: URI? = uri("/installation/repositories?per_page=100&page=1")
		var pages = 0
		while (next != null) {
			pages++
			if (pages > properties.repositoryPageCap.coerceAtLeast(1)) {
				throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "GITHUB_REPOSITORIES_TOO_LARGE", "GitHub returned too many repositories")
			}
			val response = request("GET", next, token)
			val root = parse(response)
			val repositoryArray = root.path("repositories")
			if (!repositoryArray.isArray) throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned an invalid repository response")
			repositoryArray.forEach { repositories += parseRepository(it) }
			next = nextUri(response)
		}
		return repositories.distinctBy { it.id }
	}

	override fun verifyRepositoryAccess(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
	): GitHubRepository {
		val token = installationToken(installationId, repositoryId)
		val response = request("GET", uri("/repos/${path(owner)}/${path(repository)}"), token)
		val result = parseRepository(parse(response))
		if (result.id != repositoryId) {
			throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub repository identity did not match")
		}
		return result
	}

	override fun listClosedPullRequests(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		pageCap: Int,
	): List<GitHubPullRequest> {
		val token = installationToken(installationId, repositoryId)
		val pullRequests = mutableListOf<GitHubPullRequest>()
		var next: URI? = uri("/repos/${path(owner)}/${path(repository)}/pulls?state=closed&per_page=100&page=1")
		var pages = 0
		while (next != null) {
			pages++
			if (pages > pageCap.coerceAtLeast(1)) {
				throw ApiException(HttpStatus.CONTENT_TOO_LARGE, "IMPORT_TOO_LARGE", "GitHub pull-request page cap exceeded")
			}
			val response = request("GET", next, token)
			val root = parse(response)
			if (!root.isArray) throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned an invalid pull-request response")
			root.forEach { pullRequests += parsePullRequest(it) }
			next = nextUri(response)
		}
		return pullRequests.distinctBy { it.id }
	}

	override fun listPublishedReleaseTags(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		limit: Int,
	): GitHubTagPage {
		require(limit in 1..MAX_RELEASE_TAG_SAMPLE)
		val token = installationToken(installationId, repositoryId)
		val response = request(
			"GET",
			uri("/repos/${path(owner)}/${path(repository)}/releases?per_page=100&page=1"),
			token,
		)
		val root = parse(response)
		if (!root.isArray) invalidResponse("GitHub returned invalid releases")
		val tags = buildList {
			root.forEach { release ->
				if (!release.isObject || !release.path("draft").isBoolean) {
					invalidResponse("GitHub returned an invalid release")
				}
				if (!release.path("draft").booleanValue()) {
					val publishedAt = release.path("published_at").stringValue()
						?: invalidResponse("GitHub returned an invalid published release")
					runCatching { Instant.parse(publishedAt) }
						.getOrElse { invalidResponse("GitHub returned an invalid published release") }
					add(
						release.path("tag_name").stringValue()?.takeIf { it.isNotBlank() }
							?: invalidResponse("GitHub returned an invalid release tag"),
					)
				}
			}
		}.distinct().take(limit + 1)
		return GitHubTagPage(tags.take(limit), tags.size > limit)
	}

	override fun listRepositoryTags(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		limit: Int,
	): GitHubTagPage {
		require(limit in 1..MAX_RELEASE_TAG_SAMPLE)
		val token = installationToken(installationId, repositoryId)
		val response = request(
			"GET",
			uri("/repos/${path(owner)}/${path(repository)}/tags?per_page=${limit + 1}&page=1"),
			token,
		)
		val root = parse(response)
		if (!root.isArray) invalidResponse("GitHub returned invalid repository tags")
		val tags = buildList {
			root.forEach { tag ->
				val name = tag.path("name").stringValue()?.takeIf { it.isNotBlank() }
				?: invalidResponse("GitHub returned an invalid repository tag")
				val sha = tag.path("commit").path("sha").stringValue()?.takeIf { it.isNotBlank() }
					?: invalidResponse("GitHub returned an invalid repository tag commit")
				if (sha.length != 40) invalidResponse("GitHub returned an invalid repository tag commit")
				add(name)
			}
		}.distinct().take(limit + 1)
		return GitHubTagPage(tags.take(limit), tags.size > limit)
	}

	override fun resolveTagCommit(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		tagName: String,
	): String {
		val token = installationToken(installationId, repositoryId)
		val reference = request(
			"GET",
			uri("/repos/${path(owner)}/${path(repository)}/git/ref/tags/${path(tagName)}"),
			token,
		)
		var target = parseGitObject(parse(reference).path("object"), "tag reference")
		if (target.type == "commit") return target.sha
		if (target.type != "tag") invalidTagObject(target.type)

		repeat(MAX_ANNOTATED_TAG_DEPTH) {
			val tag = request(
				"GET",
				uri("/repos/${path(owner)}/${path(repository)}/git/tags/${path(target.sha)}"),
				token,
			)
			target = parseGitObject(parse(tag).path("object"), "annotated tag")
			if (target.type == "commit") return target.sha
			if (target.type != "tag") invalidTagObject(target.type)
		}
		throw ApiException(
			HttpStatus.BAD_GATEWAY,
			"GITHUB_TAG_DEREFERENCE_LIMIT",
			"GitHub tag dereference limit exceeded",
		)
	}

	override fun compareCommits(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		baseSha: String,
		headSha: String,
		pageCap: Int,
	): GitHubCompareResult {
		val token = installationToken(installationId, repositoryId)
		val commits = mutableListOf<GitHubCommit>()
		var files = emptyList<GitHubChangedFile>()
		var status: String? = null
		var aheadBy: Int? = null
		var next: URI? = uri(
			"/repos/${path(owner)}/${path(repository)}/compare/${path(baseSha)}...${path(headSha)}?per_page=100&page=1",
		)
		var pages = 0
		val effectivePageCap = minOf(pageCap, properties.comparePageCap).coerceAtLeast(1)
		while (next != null) {
			pages++
			if (pages > effectivePageCap) {
				throw ApiException(
					HttpStatus.CONTENT_TOO_LARGE,
					"GITHUB_COMPARE_TOO_LARGE",
					"GitHub commit comparison page cap exceeded",
				)
			}
			val response = request("GET", next, token)
			val root = parse(response)
			if (!root.isObject) invalidResponse("GitHub returned an invalid comparison response")
			if (pages == 1) {
				status = root.path("status").stringValue()?.takeIf { it in COMPARE_STATUSES }
					?: invalidResponse("GitHub returned an invalid comparison status")
				aheadBy = root.path("ahead_by").takeIf { it.canConvertToInt() }?.intValue()
					?.takeIf { it >= 0 }
					?: invalidResponse("GitHub returned an invalid comparison count")
				val fileArray = root.path("files")
				if (!fileArray.isMissingNode && !fileArray.isArray) {
					invalidResponse("GitHub returned invalid changed files")
				}
				if (fileArray.isArray) {
					files = buildList {
						fileArray.forEach { add(parseChangedFile(it)) }
					}
				}
			}
			val commitArray = root.path("commits")
			if (!commitArray.isArray) invalidResponse("GitHub returned invalid comparison commits")
			commitArray.forEach { commits += parseCommit(it) }
			next = nextUri(response)
			if (next != null && pages == effectivePageCap) {
				throw ApiException(
					HttpStatus.CONTENT_TOO_LARGE,
					"GITHUB_COMPARE_TOO_LARGE",
					"GitHub commit comparison page cap exceeded",
				)
			}
		}
		val resolvedStatus = checkNotNull(status)
		val resolvedAheadBy = checkNotNull(aheadBy)
		val uniqueCommits = commits.distinctBy { it.sha }
		if (resolvedStatus in COMMIT_COUNT_STATUSES && uniqueCommits.size != resolvedAheadBy) {
			invalidResponse("GitHub returned an incomplete commit comparison")
		}
		return GitHubCompareResult(
			status = resolvedStatus,
			aheadBy = resolvedAheadBy,
			commits = uniqueCommits,
			files = files,
			filesTruncated = files.size >= GITHUB_COMPARE_FILE_LIMIT,
		)
	}

	override fun listPullRequestsForCommit(
		installationId: Long,
		repositoryId: Long,
		owner: String,
		repository: String,
		commitSha: String,
	): List<GitHubPullRequest> {
		val token = installationToken(installationId, repositoryId)
		val response = request(
			"GET",
			uri("/repos/${path(owner)}/${path(repository)}/commits/${path(commitSha)}/pulls?per_page=100&page=1"),
			token,
		)
		val root = parse(response)
		if (!root.isArray) invalidResponse("GitHub returned an invalid associated pull-request response")
		return buildList {
			root.forEach { add(parsePullRequest(it)) }
		}.distinctBy { it.id }
	}

	private fun installationToken(installationId: Long, repositoryId: Long? = null): String {
		val tokenRequest = mutableMapOf<String, Any>(
			"permissions" to mapOf(
				"contents" to "read",
				"metadata" to "read",
				"pull_requests" to "read",
			),
		)
		repositoryId?.let {
			tokenRequest["repository_ids"] = listOf(it)
		}
		val body = objectMapper.writeValueAsString(tokenRequest)
		val response = request(
			"POST",
			uri("/app/installations/$installationId/access_tokens"),
			appJwt(),
			body,
			bearerScheme = "Bearer",
		)
		val token = parse(response).path("token").stringValue()
		if (token.isNullOrBlank()) throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub did not return an installation token")
		return token
	}

	private fun appJwt(): String {
		val appId = properties.appId?.takeIf { it.isNotBlank() }
			?: throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_NOT_CONFIGURED", "GitHub is not configured")
		val privateKey = parsePrivateKey(properties.privateKey)
		val now = Instant.now(clock).epochSecond
		val header = base64Json(mapOf("alg" to "RS256", "typ" to "JWT"))
		val payload = base64Json(mapOf("iat" to now - 60, "exp" to now + 540, "iss" to appId))
		val signingInput = "$header.$payload"
		val signature = Signature.getInstance("SHA256withRSA").apply {
			initSign(privateKey)
			update(signingInput.toByteArray(StandardCharsets.UTF_8))
		}.sign()
		return "$signingInput.${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"
	}

	private fun parsePrivateKey(value: String?): PrivateKey {
		val pem = value?.takeIf { it.isNotBlank() }
			?: throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_NOT_CONFIGURED", "GitHub is not configured")
		val der = try {
			Base64.getDecoder().decode(
				pem.replace(Regex("-----BEGIN [^-]+-----|-----END [^-]+-----|\\s"), ""),
			)
		} catch (_: IllegalArgumentException) {
			throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_KEY_INVALID", "GitHub private key is invalid")
		}
		val pkcs8 = if (pem.contains("BEGIN RSA PRIVATE KEY")) wrapPkcs1InPkcs8(der) else der
		return try {
			KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8)).also { key ->
				if ((key as? RSAKey)?.modulus?.bitLength()?.let { it < 2048 } != false) {
					throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_KEY_INVALID", "GitHub private key is invalid")
				}
			}
		} catch (exception: ApiException) {
			throw exception
		} catch (_: Exception) {
			throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_KEY_INVALID", "GitHub private key is invalid")
		}
	}

	private fun wrapPkcs1InPkcs8(pkcs1: ByteArray): ByteArray {
		val prefix = byteArrayOf(
			0x30, 0x0d, 0x06, 0x09, 0x2a.toByte(), 0x86.toByte(), 0x48, 0x86.toByte(),
			0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
		)
		val body = byteArrayOf(0x02, 0x01, 0x00) + prefix + byteArrayOf(0x04) + derLength(pkcs1.size) + pkcs1
		return byteArrayOf(0x30) + derLength(body.size) + body
	}

	private fun derLength(length: Int): ByteArray {
		if (length < 128) return byteArrayOf(length.toByte())
		val bytes = BigInteger.valueOf(length.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
		return byteArrayOf((0x80 or bytes.size).toByte()) + bytes
	}

	private fun request(
		method: String,
		uri: URI,
		token: String,
		body: String? = null,
		bearerScheme: String = "Bearer",
	): GitHubHttpResponse {
		val response = try {
			transport.execute(
				method,
				uri,
				mapOf(
					"Accept" to "application/vnd.github+json",
					"X-GitHub-Api-Version" to "2022-11-28",
					"Authorization" to "$bearerScheme $token",
					"Content-Type" to "application/json",
				),
				body,
			)
		} catch (exception: ApiException) {
			throw exception
		} catch (_: Exception) {
			throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_NETWORK_ERROR", "GitHub request failed")
		}
		if (response.status !in 200..299) throw providerError(response)
		return response
	}

	private fun providerError(response: GitHubHttpResponse): ApiException {
		val requestId = response.headers.entries
			.firstOrNull { it.key.equals("x-github-request-id", ignoreCase = true) }
			?.value?.firstOrNull()
		val suffix = requestId?.let { " (request $it)" }.orEmpty()
		val (status, code, message) = when {
			response.status == 429 ||
				(response.status == 403 && response.headers.entries.any {
					(it.key.equals("x-ratelimit-remaining", ignoreCase = true) && it.value.firstOrNull() == "0") ||
						it.key.equals("retry-after", ignoreCase = true)
				}) ||
				(response.status == 403 && response.body.contains("rate limit", ignoreCase = true)) ->
				Triple(HttpStatus.TOO_MANY_REQUESTS, "GITHUB_RATE_LIMITED", "GitHub rate limit exceeded$suffix")
			response.status == 401 || response.status == 403 -> Triple(HttpStatus.BAD_GATEWAY, "GITHUB_ACCESS_DENIED", "GitHub denied access$suffix")
			response.status == 404 -> Triple(HttpStatus.BAD_GATEWAY, "GITHUB_NOT_FOUND", "GitHub resource was not found$suffix")
			response.status >= 500 -> Triple(HttpStatus.BAD_GATEWAY, "GITHUB_PROVIDER_UNAVAILABLE", "GitHub is temporarily unavailable$suffix")
			else -> Triple(HttpStatus.BAD_GATEWAY, "GITHUB_PROVIDER_ERROR", "GitHub request failed$suffix")
		}
		return ApiException(status, code, message)
	}

	private fun parse(response: GitHubHttpResponse): JsonNode = try {
		objectMapper.readTree(response.body)
	} catch (_: Exception) {
		throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned invalid JSON")
	}

	private fun parseRepository(node: JsonNode): GitHubRepository {
		val id = node.path("id").longValue()
		val owner = node.path("owner").path("login").stringValue()
		val name = node.path("name").stringValue()
		if (id == 0L || owner.isNullOrBlank() || name.isNullOrBlank()) {
			throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned an invalid repository")
		}
		return GitHubRepository(
			id = id,
			owner = owner,
			name = name,
			url = node.path("html_url").stringValue().orEmpty(),
			defaultBranch = node.path("default_branch").takeUnless { it.isMissingNode || it.isNull }?.stringValue(),
			ownerId = node.path("owner").path("id").takeUnless { it.isMissingNode || it.isNull }
				?.longValue()?.takeIf { it > 0L },
		)
	}

	private fun parsePullRequest(node: JsonNode): GitHubPullRequest {
		val id = node.path("id").longValue()
		val number = node.path("number").intValue()
		val title = node.path("title").stringValue().orEmpty()
		val body = node.path("body").takeUnless { it.isNull }?.stringValue()
		val createdAt = instant(node.path("created_at"))
		val updatedAt = instant(node.path("updated_at"))
		val url = canonicalWebUrl(node.path("html_url"))
		if (
			id == 0L || number == 0 || createdAt == null || updatedAt == null || url == null ||
			(title.isBlank() && body.isNullOrBlank())
		) {
			throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned an invalid pull request")
		}
		return GitHubPullRequest(
			id = id,
			number = number,
			title = title,
			body = body,
			author = node.path("user").path("login").stringValue(),
			url = url,
			baseBranch = node.path("base").path("ref").stringValue(),
			headBranch = node.path("head").path("ref").stringValue(),
			createdAt = createdAt,
			updatedAt = updatedAt,
			mergedAt = instant(node.path("merged_at")),
		)
	}

	private fun parseCommit(node: JsonNode): GitHubCommit {
		val sha = node.path("sha").stringValue()?.takeIf { it.isNotBlank() }
		val details = node.path("commit")
		val message = details.path("message").stringValue()
		val author = node.path("author").path("login").takeUnless { it.isMissingNode || it.isNull }
			?.stringValue()?.takeIf { it.isNotBlank() }
			?: details.path("author").path("name").takeUnless { it.isMissingNode || it.isNull }
				?.stringValue()?.takeIf { it.isNotBlank() }
		val url = canonicalWebUrl(node.path("html_url"))
		if (sha == null || message == null || url == null || !details.isObject) {
			invalidResponse("GitHub returned an invalid commit")
		}
		return GitHubCommit(
			sha = sha,
			message = message,
			author = author,
			committedAt = instant(details.path("committer").path("date"))
				?: instant(details.path("author").path("date")),
			url = url,
		)
	}

	private fun parseChangedFile(node: JsonNode): GitHubChangedFile {
		val filename = node.path("filename").stringValue()?.takeIf { it.isNotBlank() }
		val status = node.path("status").stringValue()?.takeIf { it.isNotBlank() }
		val additions = node.path("additions").takeIf { it.canConvertToInt() }?.intValue()?.takeIf { it >= 0 }
		val deletions = node.path("deletions").takeIf { it.canConvertToInt() }?.intValue()?.takeIf { it >= 0 }
		if (filename == null || status == null || additions == null || deletions == null) {
			invalidResponse("GitHub returned an invalid changed file")
		}
		return GitHubChangedFile(
			filename = filename,
			previousFilename = node.path("previous_filename").takeUnless { it.isMissingNode || it.isNull }
				?.stringValue()?.takeIf { it.isNotBlank() },
			status = status,
			additions = additions,
			deletions = deletions,
			patch = node.path("patch").takeUnless { it.isMissingNode || it.isNull }?.stringValue(),
		)
	}

	private fun parseGitObject(node: JsonNode, source: String): GitObjectTarget {
		val type = node.path("type").stringValue()?.takeIf { it.isNotBlank() }
		val sha = node.path("sha").stringValue()?.takeIf { it.isNotBlank() }
		if (!node.isObject || type == null || sha == null) {
			invalidResponse("GitHub returned an invalid $source")
		}
		return GitObjectTarget(type, sha)
	}

	private fun invalidTagObject(type: String): Nothing = throw ApiException(
		HttpStatus.BAD_GATEWAY,
		"GITHUB_TAG_INVALID_OBJECT",
		"GitHub tag resolved to an invalid object type: $type",
	)

	private fun invalidResponse(message: String): Nothing = throw ApiException(
		HttpStatus.BAD_GATEWAY,
		"GITHUB_INVALID_RESPONSE",
		message,
	)

	private fun canonicalWebUrl(node: JsonNode): String? {
		val value = node.stringValue()?.takeIf { it.isNotBlank() } ?: return null
		val candidate = runCatching { URI.create(value) }.getOrNull() ?: return null
		val canonical = runCatching { URI.create(properties.webBaseUrl) }.getOrNull() ?: return null
		val candidateHost = candidate.host ?: return null
		val canonicalHost = canonical.host ?: return null
		return value.takeIf {
			candidate.scheme == canonical.scheme &&
				candidateHost.equals(canonicalHost, ignoreCase = true) &&
				candidate.port == canonical.port &&
				candidate.userInfo == null
		}
	}

	private fun instant(node: JsonNode): Instant? = node.takeUnless { it.isMissingNode || it.isNull || it.stringValue().isNullOrBlank() }
		?.let { runCatching { Instant.parse(it.stringValue()) }.getOrNull() }

	private fun nextUri(response: GitHubHttpResponse): URI? {
		val value = response.headers.entries
			.firstOrNull { it.key.equals("link", ignoreCase = true) }
			?.value?.joinToString(",")
			?: return null
		val next = Regex("<([^>]+)>\\s*;\\s*rel=\"next\"").find(value)?.groupValues?.get(1) ?: return null
		val uri = runCatching { URI.create(next) }.getOrNull() ?: throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned an invalid pagination link")
		val base = URI.create(properties.apiBaseUrl)
		if (uri.scheme != base.scheme || uri.host != base.host || uri.port != base.port) {
			throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_REDIRECT_REJECTED", "GitHub returned an untrusted pagination link")
		}
		return uri
	}

	private fun uri(path: String): URI = URI.create(properties.apiBaseUrl.trimEnd('/') + path)

	private fun path(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

	private fun base64Json(value: Any): String = Base64.getUrlEncoder().withoutPadding()
		.encodeToString(objectMapper.writeValueAsBytes(value))

	private data class GitObjectTarget(val type: String, val sha: String)

	private companion object {
		const val MAX_ANNOTATED_TAG_DEPTH = 5
		const val MAX_RELEASE_TAG_SAMPLE = 50
		const val GITHUB_COMPARE_FILE_LIMIT = 300
		val COMPARE_STATUSES = setOf("ahead", "behind", "diverged", "identical")
		val COMMIT_COUNT_STATUSES = setOf("ahead", "diverged")
	}
}
