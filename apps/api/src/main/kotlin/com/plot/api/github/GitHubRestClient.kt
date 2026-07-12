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
class JavaGitHubHttpTransport : GitHubHttpTransport {
	private val client: HttpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.build()

	override fun execute(
		method: String,
		uri: URI,
		headers: Map<String, String>,
		body: String?,
	): GitHubHttpResponse {
		val builder = HttpRequest.newBuilder(uri)
			.timeout(java.time.Duration.ofSeconds(30))
			.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
		headers.forEach { (name, value) -> builder.header(name, value) }
		val response = try {
			client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw exception
		}
		return GitHubHttpResponse(response.statusCode(), response.headers().map(), response.body())
	}
}

@Component
class GitHubRestClient(
	private val properties: GitHubProperties,
	private val objectMapper: ObjectMapper,
	private val clock: Clock = Clock.systemUTC(),
	private val transport: GitHubHttpTransport = JavaGitHubHttpTransport(),
) : GitHubClient {

	override fun listInstallationRepositories(installationId: Long): List<GitHubRepository> {
		val token = installationToken(installationId)
		val repositories = mutableListOf<GitHubRepository>()
		var next: URI? = uri("/installation/repositories?per_page=100&page=1")
		var pages = 0
		while (next != null) {
			pages++
			if (pages > properties.repositoryPageCap.coerceAtLeast(1)) {
				throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "GITHUB_REPOSITORIES_TOO_LARGE", "GitHub returned too many repositories")
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
				throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMPORT_TOO_LARGE", "GitHub pull-request page cap exceeded")
			}
			val response = request("GET", next, token)
			val root = parse(response)
			if (!root.isArray) throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned an invalid pull-request response")
			root.forEach { pullRequests += parsePullRequest(it) }
			next = nextUri(response)
		}
		return pullRequests.distinctBy { it.id }
	}

	private fun installationToken(installationId: Long, repositoryId: Long? = null): String {
		val tokenRequest = mutableMapOf<String, Any>(
			"permissions" to mapOf(
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
		val token = parse(response).path("token").textValue()
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
			(response.status == 403 || response.status == 429) && response.headers.entries.any {
				it.key.equals("x-ratelimit-remaining", ignoreCase = true) && it.value.firstOrNull() == "0"
			} -> Triple(HttpStatus.TOO_MANY_REQUESTS, "GITHUB_RATE_LIMITED", "GitHub rate limit exceeded$suffix")
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
		val owner = node.path("owner").path("login").textValue()
		val name = node.path("name").textValue()
		if (id == 0L || owner.isNullOrBlank() || name.isNullOrBlank()) {
			throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned an invalid repository")
		}
		return GitHubRepository(
			id = id,
			owner = owner,
			name = name,
			url = node.path("html_url").textValue().orEmpty(),
			defaultBranch = node.path("default_branch").takeUnless { it.isMissingNode || it.isNull }?.textValue(),
			ownerId = node.path("owner").path("id").takeUnless { it.isMissingNode || it.isNull }
				?.longValue()?.takeIf { it > 0L },
		)
	}

	private fun parsePullRequest(node: JsonNode): GitHubPullRequest {
		val id = node.path("id").longValue()
		val number = node.path("number").intValue()
		val title = node.path("title").textValue().orEmpty()
		val body = node.path("body").takeUnless { it.isNull }?.textValue()
		val createdAt = instant(node.path("created_at"))
		val updatedAt = instant(node.path("updated_at"))
		if (id == 0L || number == 0 || createdAt == null || updatedAt == null || (title.isBlank() && body.isNullOrBlank())) {
			throw ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", "GitHub returned an invalid pull request")
		}
		return GitHubPullRequest(
			id = id,
			number = number,
			title = title,
			body = body,
			author = node.path("user").path("login").textValue(),
			url = node.path("html_url").textValue().orEmpty(),
			baseBranch = node.path("base").path("ref").textValue(),
			headBranch = node.path("head").path("ref").textValue(),
			createdAt = createdAt,
			updatedAt = updatedAt,
			mergedAt = instant(node.path("merged_at")),
		)
	}

	private fun instant(node: JsonNode): Instant? = node.takeUnless { it.isMissingNode || it.isNull || it.textValue().isNullOrBlank() }
		?.let { runCatching { Instant.parse(it.textValue()) }.getOrNull() }

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
}
