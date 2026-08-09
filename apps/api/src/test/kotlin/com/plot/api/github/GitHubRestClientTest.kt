package com.plot.api.github

import com.plot.api.common.ApiException
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class GitHubRestClientTest {
	private val objectMapper = ObjectMapper()

	@Test
	fun javaTransportAppliesTheConfiguredRequestTimeout() {
		val timeout = Duration.ofSeconds(7)
		val request = JavaGitHubHttpTransport(
			GitHubProperties(
				httpRequestTimeout = timeout,
				monitoringAnalysisLeaseTimeout = Duration.ofMinutes(1),
			),
		).buildRequest(
			method = "GET",
			uri = java.net.URI("https://api.github.test/repos/acme/plot"),
			headers = mapOf("Accept" to "application/json"),
			body = null,
		)

		assertEquals(timeout, request.timeout().orElseThrow())
	}

	@Test
	fun monitoringHttpEnvelopeMustBeShorterThanTheLease() {
		assertFailsWith<IllegalArgumentException> {
			GitHubProperties(
				httpRequestTimeout = Duration.ofSeconds(30),
				monitoringAnalysisLeaseTimeout = Duration.ofMinutes(2),
			)
		}
	}

	@Test
	fun signsBoundedRs256AppJwtAndRequestsLeastPrivilegeRepositoryToken() {
		val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
		var tokenAuthorization: String? = null
		var tokenBody: String? = null
		val transport = GitHubHttpTransport { method, uri, headers, body ->
			when {
				uri.path.endsWith("/access_tokens") -> {
					tokenAuthorization = headers["Authorization"]
					tokenBody = body
					GitHubHttpResponse(201, emptyMap(), "{\"token\":\"installation-token\"}")
				}
				else -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""{"id":44,"name":"plot","owner":{"login":"acme"},"html_url":"https://github.test/acme/plot","default_branch":"main"}""",
				)
			}
		}
		val now = Instant.parse("2026-01-10T00:00:00Z")
		val client = GitHubRestClient(
			properties(testPrivateKey(keyPair)),
			objectMapper,
			Clock.fixed(now, ZoneOffset.UTC),
			transport,
		)

		client.verifyRepositoryAccess(77, 44, "acme", "plot")

		val jwt = tokenAuthorization!!.removePrefix("Bearer ")
		val parts = jwt.split('.')
		assertEquals(3, parts.size)
		assertEquals("RS256", decodeJwtPart(parts[0]).path("alg").stringValue())
		val claims = decodeJwtPart(parts[1])
		assertEquals(now.epochSecond - 60, claims.path("iat").longValue())
		assertEquals(now.epochSecond + 540, claims.path("exp").longValue())
		assertEquals("123", claims.path("iss").stringValue())
		assertTrue(Signature.getInstance("SHA256withRSA").run {
			initVerify(keyPair.public)
			update("${parts[0]}.${parts[1]}".toByteArray())
			verify(Base64.getUrlDecoder().decode(parts[2]))
		})
		val request = objectMapper.readTree(tokenBody!!)
		assertEquals(listOf(44L), request.path("repository_ids").toList().map { it.longValue() })
		assertEquals("read", request.path("permissions").path("metadata").stringValue())
		assertEquals("read", request.path("permissions").path("pull_requests").stringValue())
		assertTrue(jwt.contains("PRIVATE KEY").not())
	}

	@Test
	fun rejectsWeakRsaPrivateKeysBeforeMakingARequest() {
		val weakKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
		var called = false
		val client = GitHubRestClient(
			properties(testPrivateKey(weakKey)),
			objectMapper,
			transport = GitHubHttpTransport { _, _, _, _ ->
				called = true
				GitHubHttpResponse(500, emptyMap(), "secret")
			},
		)

		val exception = assertFailsWith<ApiException> { client.listInstallationRepositories(77) }

		assertEquals("GITHUB_KEY_INVALID", exception.error)
		assertEquals(false, called)
	}

	@Test
	fun repositoryDiscoveryTokenKeepsReadOnlyPermissionsWithoutRestrictingRepositoryIds() {
		var tokenBody: String? = null
		val transport = GitHubHttpTransport { _, uri, _, body ->
			when {
				uri.path.endsWith("/access_tokens") -> {
					tokenBody = body
					GitHubHttpResponse(201, emptyMap(), "{\"token\":\"installation-token\"}")
				}
				else -> GitHubHttpResponse(200, emptyMap(), "{\"repositories\":[]}")
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		client.listInstallationRepositories(77)

		val request = objectMapper.readTree(tokenBody!!)
		assertEquals(false, request.has("repository_ids"))
		assertEquals("read", request.path("permissions").path("metadata").stringValue())
		assertEquals("read", request.path("permissions").path("pull_requests").stringValue())
	}

	@Test
	fun readsBoundedPublishedReleaseTagsAndIgnoresDrafts() {
		var requestedPath: String? = null
		var requestedQuery: String? = null
		val releases = buildString {
			append("[")
			append("""{"tag_name":"draft","draft":true,"published_at":null},""")
			(1..51).forEach { index ->
				if (index > 1) append(",")
				append("""{"tag_name":"v$index.0.0","draft":false,"published_at":"2026-01-01T00:00:00Z"}""")
			}
			append("]")
		}
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") ->
					GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				else -> {
					requestedPath = uri.rawPath
					requestedQuery = uri.rawQuery
					GitHubHttpResponse(200, emptyMap(), releases)
				}
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val result = client.listPublishedReleaseTags(77, 44, "ac me", "plot/repo", 50)

		assertEquals((1..50).map { "v$it.0.0" }, result.tags)
		assertTrue(result.truncated)
		assertEquals("/repos/ac%20me/plot%2Frepo/releases", requestedPath)
		assertEquals("per_page=100&page=1", requestedQuery)
	}

	@Test
	fun rejectsPublishedReleaseWithInvalidPublishedAt() {
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			if (uri.path.endsWith("/access_tokens")) {
				GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
			} else {
				GitHubHttpResponse(
					200,
					emptyMap(),
					"""[{"tag_name":"v1.0.0","draft":false,"published_at":"not-an-instant"}]""",
				)
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val exception = assertFailsWith<ApiException> {
			client.listPublishedReleaseTags(77, 44, "acme", "plot", 50)
		}

		assertEquals("GITHUB_INVALID_RESPONSE", exception.error)
	}

	@Test
	fun readsABoundedRepositoryTagFallbackSample() {
		var requestedQuery: String? = null
		val tags = (1..51).joinToString(prefix = "[", postfix = "]") { index ->
			"""{"name":"v$index.0.0","commit":{"sha":"${index.toString().padStart(40, '0')}"}}"""
		}
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") ->
					GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				else -> {
					requestedQuery = uri.rawQuery
					GitHubHttpResponse(200, emptyMap(), tags)
				}
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val result = client.listRepositoryTags(77, 44, "acme", "plot", 50)

		assertEquals((1..50).map { "v$it.0.0" }, result.tags)
		assertTrue(result.truncated)
		assertEquals("per_page=51&page=1", requestedQuery)
	}

	@Test
	fun followsClosedPullRequestPagesAndUsesInstallationToken() {
		val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
		val transport = GitHubHttpTransport { method, uri, headers, body ->
			calls += Triple(method, uri.toString(), headers)
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), "{\"token\":\"installation-token\"}")
				Regex("(?:^|&)page=1(?:&|$)").containsMatchIn(uri.query.orEmpty()) -> GitHubHttpResponse(
					200,
					mapOf("Link" to listOf("<https://api.github.test/repos/acme/plot/pulls?state=closed&per_page=100&page=2>; rel=\"next\"")),
					"""
					[
					 {"id":11,"number":1,"title":"First","body":null,"html_url":"https://github.test/acme/plot/pull/1","user":{"login":"ada"},"base":{"ref":"main"},"head":{"ref":"feature"},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z","merged_at":"2026-01-02T00:00:00Z"}
					]
					""".trimIndent(),
				)
				else -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""
					[{"id":12,"number":2,"title":"Second","body":"Body","html_url":"https://github.test/acme/plot/pull/2","user":{"login":"grace"},"base":{"ref":"main"},"head":{"ref":"feature-2"},"created_at":"2026-01-03T00:00:00Z","updated_at":"2026-01-04T00:00:00Z","merged_at":null}]
					""".trimIndent(),
				)
			}
		}
		val properties = GitHubProperties(
			enabled = true,
			appId = "123",
			appSlug = "plot",
			privateKey = testPrivateKey(),
			stateSecret = "state-secret",
			apiBaseUrl = "https://api.github.test",
			webBaseUrl = "https://github.test",
		)
		val client = GitHubRestClient(properties, ObjectMapper(), Clock.fixed(Instant.parse("2026-01-10T00:00:00Z"), ZoneOffset.UTC), transport)

		val result = client.listClosedPullRequests(77, 44, "acme", "plot", 3)

		assertEquals(listOf(11L, 12L), result.map { it.id })
		assertEquals(3, calls.size)
		assertTrue(calls.all { it.third["Authorization"] == "Bearer installation-token" || it.third["Authorization"]?.startsWith("Bearer ey") == true })
		assertTrue(calls.last().second.contains("page=2"))
	}

	@Test
	fun returnsOneClosedPullRequestPageWithATrustedContinuation() {
		var pullPageCalls = 0
		val next = "https://api.github.test/repos/acme/plot/pulls?state=closed&per_page=100&page=2"
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") ->
					GitHubHttpResponse(201, emptyMap(), "{\"token\":\"installation-token\"}")
				else -> {
					pullPageCalls++
					GitHubHttpResponse(
						200,
						mapOf("Link" to listOf("<$next>; rel=\"next\"")),
						"""[{"id":11,"number":1,"title":"First","body":null,"html_url":"https://github.com/acme/plot/pull/1","user":{"login":"ada"},"base":{"ref":"main"},"head":{"ref":"feature"},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z","merged_at":"2026-01-02T00:00:00Z"}]""",
					)
				}
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val page = client.listClosedPullRequestsPage(77, 44, "acme", "plot", continuation = null)

		assertEquals(listOf(11L), page.pullRequests.map { it.id })
		assertEquals(next, page.nextPage)
		assertEquals(1, pullPageCalls)
	}

	@Test
	fun rejectsAnUntrustedPersistedPullRequestContinuationBeforeHttp() {
		var calls = 0
		val client = GitHubRestClient(
			properties(),
			objectMapper,
			transport = GitHubHttpTransport { _, _, _, _ ->
				calls++
				GitHubHttpResponse(500, emptyMap(), "provider secret")
			},
		)

		val exception = assertFailsWith<ApiException> {
			client.listClosedPullRequestsPage(
				77,
				44,
				"acme",
				"plot",
				"https://user@api.github.test/repos/acme/plot/pulls?page=2",
			)
		}

		assertEquals("GITHUB_REDIRECT_REJECTED", exception.error)
		assertEquals(0, calls)
	}

	@Test
	fun rejectsPageCapAndUntrustedPaginationLinksBeforeReturningPartialData() {
		val transport = GitHubHttpTransport { method, uri, headers, body ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), "{\"token\":\"installation-token\"}")
				else -> GitHubHttpResponse(
					200,
					mapOf("Link" to listOf("<https://evil.example/repos/acme/plot/pulls?page=2>; rel=\"next\"")),
					"[]",
				)
			}
		}
		val properties = GitHubProperties(
			enabled = true,
			appId = "123",
			appSlug = "plot",
			privateKey = testPrivateKey(),
			stateSecret = "state-secret",
			apiBaseUrl = "https://api.github.test",
		)
		val client = GitHubRestClient(properties, ObjectMapper(), Clock.systemUTC(), transport)

		val exception = assertFailsWith<ApiException> {
			client.listClosedPullRequests(77, 44, "acme", "plot", 1)
		}
		assertEquals("GITHUB_REDIRECT_REJECTED", exception.error)
	}

	@Test
	fun reportsImportTooLargeWhenATrustedNextPageExceedsTheCap() {
		var pullPageCalls = 0
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), "{\"token\":\"installation-token\"}")
				else -> {
					pullPageCalls++
					GitHubHttpResponse(
						200,
						mapOf("Link" to listOf("<https://api.github.test/repos/acme/plot/pulls?page=2>; rel=\"next\"")),
						"[]",
					)
				}
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val exception = assertFailsWith<ApiException> {
			client.listClosedPullRequests(77, 44, "acme", "plot", 1)
		}

		assertEquals("IMPORT_TOO_LARGE", exception.error)
		assertEquals(1, pullPageCalls)
	}

	@Test
	fun mapsTransportFailuresWithoutExposingProviderDetails() {
		val transport = GitHubHttpTransport { _, _, _, _ -> throw java.io.IOException("secret provider body") }
		val properties = GitHubProperties(
			enabled = true,
			appId = "123",
			appSlug = "plot",
			privateKey = testPrivateKey(),
			stateSecret = "state-secret",
			apiBaseUrl = "https://api.github.test",
		)
		val client = GitHubRestClient(properties, ObjectMapper(), Clock.systemUTC(), transport)

		val exception = assertFailsWith<ApiException> { client.listInstallationRepositories(77) }
		assertEquals("GITHUB_NETWORK_ERROR", exception.error)
		assertEquals("GitHub request failed", exception.message)
	}

	@Test
	fun resolvesLightweightTagToCommitWithEncodedPathAndContentsPermission() {
		var tokenBody: String? = null
		var referenceRawPath: String? = null
		val transport = GitHubHttpTransport { _, uri, _, body ->
			when {
				uri.path.endsWith("/access_tokens") -> {
					tokenBody = body
					GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				}
				else -> {
					referenceRawPath = uri.rawPath
					GitHubHttpResponse(
						200,
						emptyMap(),
						"""{"ref":"refs/tags/release/한 글","object":{"type":"commit","sha":"head-sha","url":"https://api.github.test/repos/acme/plot/git/commits/head-sha"}}""",
					)
				}
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val result = client.resolveTagCommit(77, 44, "ac me", "plot/repo", "release/한 글")

		assertEquals("head-sha", result)
		assertEquals(
			"/repos/ac%20me/plot%2Frepo/git/ref/tags/release%2F%ED%95%9C%20%EA%B8%80",
			referenceRawPath,
		)
		val permissions = objectMapper.readTree(tokenBody!!).path("permissions")
		assertEquals("read", permissions.path("contents").stringValue())
	}

	@Test
	fun dereferencesNestedAnnotatedTagsUntilACommit() {
		val requestedPaths = mutableListOf<String>()
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				uri.rawPath.endsWith("/git/ref/tags/v1") -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""{"ref":"refs/tags/v1","object":{"type":"tag","sha":"tag-one","url":"https://api.github.test/repos/acme/plot/git/tags/tag-one"}}""",
				)
				uri.rawPath.endsWith("/git/tags/tag-one") -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""{"sha":"tag-one","object":{"type":"tag","sha":"tag-two","url":"https://api.github.test/repos/acme/plot/git/tags/tag-two"}}""",
				)
				else -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""{"sha":"tag-two","object":{"type":"commit","sha":"commit-sha","url":"https://api.github.test/repos/acme/plot/git/commits/commit-sha"}}""",
				)
			}.also { if (!uri.path.endsWith("/access_tokens")) requestedPaths += uri.rawPath }
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		assertEquals("commit-sha", client.resolveTagCommit(77, 44, "acme", "plot", "v1"))
		assertEquals(
			listOf(
				"/repos/acme/plot/git/ref/tags/v1",
				"/repos/acme/plot/git/tags/tag-one",
				"/repos/acme/plot/git/tags/tag-two",
			),
			requestedPaths,
		)
	}

	@Test
	fun rejectsWhenTheFifthAnnotatedTagPointsToASixthTag() {
		var tagCalls = 0
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				uri.rawPath.contains("/git/ref/tags/") -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""{"object":{"type":"tag","sha":"tag-1","url":"https://api.github.test/tag-1"}}""",
				)
				else -> {
					tagCalls++
					GitHubHttpResponse(
						200,
						emptyMap(),
						"""{"object":{"type":"tag","sha":"tag-${tagCalls + 1}","url":"https://api.github.test/tag-${tagCalls + 1}"}}""",
					)
				}
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val exception = assertFailsWith<ApiException> {
			client.resolveTagCommit(77, 44, "acme", "plot", "v1")
		}

		assertEquals("GITHUB_TAG_DEREFERENCE_LIMIT", exception.error)
		assertEquals(5, tagCalls)
	}

	@Test
	fun resolvesACommitAtExactlyTheFifthAnnotatedTagDereference() {
		var tagCalls = 0
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				uri.rawPath.contains("/git/ref/tags/") -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""{"object":{"type":"tag","sha":"tag-1","url":"https://api.github.test/tag-1"}}""",
				)
				else -> {
					tagCalls++
					val type = if (tagCalls == 5) "commit" else "tag"
					GitHubHttpResponse(
						200,
						emptyMap(),
						"""{"object":{"type":"$type","sha":"object-${tagCalls + 1}","url":"https://api.github.test/object-${tagCalls + 1}"}}""",
					)
				}
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		assertEquals("object-6", client.resolveTagCommit(77, 44, "acme", "plot", "v1"))
		assertEquals(5, tagCalls)
	}

	@Test
	fun rejectsTagResolvingToANonCommitObjectPermanently() {
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				else -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""{"object":{"type":"tree","sha":"tree-sha","url":"https://api.github.test/tree-sha"}}""",
				)
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val exception = assertFailsWith<ApiException> {
			client.resolveTagCommit(77, 44, "acme", "plot", "v1")
		}

		assertEquals("GITHUB_TAG_INVALID_OBJECT", exception.error)
	}

	@Test
	fun paginatesCompareCommitsAndUsesChangedFilesOnlyFromTheFirstPage() {
		val compareUris = mutableListOf<String>()
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				Regex("(?:^|&)page=1(?:&|$)").containsMatchIn(uri.query.orEmpty()) -> GitHubHttpResponse(
					200,
					mapOf("Link" to listOf("<https://api.github.test/repos/acme/plot/compare/base%2Fsha...head%2Fsha?per_page=100&page=2>; rel=\"next\"")),
					"""
					{
					  "status":"ahead",
					  "ahead_by":2,
					  "commits":[{
					    "sha":"commit-1",
					    "html_url":"https://github.com/acme/plot/commit/commit-1",
					    "author":{"login":"ada"},
					    "commit":{"message":"First","committer":{"date":"2026-01-01T00:00:00Z"}}
					  }],
					  "files":[{
					    "filename":"new.kt","previous_filename":"old.kt","status":"renamed",
					    "additions":3,"deletions":1,"patch":"@@ patch one"
					  }]
					}
					""".trimIndent(),
				)
				else -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""
					{
					  "status":"ahead",
					  "ahead_by":2,
					  "commits":[{
					    "sha":"commit-2",
					    "html_url":"https://github.com/acme/plot/commit/commit-2",
					    "author":null,
					    "commit":{"message":"Second","author":{"name":"Grace"},"committer":{"date":"2026-01-02T00:00:00Z"}}
					  }],
					  "files":[{
					    "filename":"ignored.kt","status":"modified","additions":99,"deletions":0,"patch":"ignored"
					  }]
					}
					""".trimIndent(),
				)
			}.also { if (!uri.path.endsWith("/access_tokens")) compareUris += uri.toString() }
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val result = client.compareCommits(77, 44, "acme", "plot", "base/sha", "head/sha", 3)

		assertEquals("ahead", result.status)
		assertEquals(2, result.aheadBy)
		assertEquals(listOf("commit-1", "commit-2"), result.commits.map { it.sha })
		assertEquals(listOf("ada", "Grace"), result.commits.map { it.author })
		assertEquals(
			listOf(
				"https://github.com/acme/plot/commit/commit-1",
				"https://github.com/acme/plot/commit/commit-2",
			),
			result.commits.map { it.url },
		)
		assertEquals(listOf("new.kt"), result.files.map { it.filename })
		assertEquals("old.kt", result.files.single().previousFilename)
		assertFalse(result.filesTruncated)
		assertTrue(compareUris.first().contains("/compare/base%2Fsha...head%2Fsha?per_page=100&page=1"))
		assertTrue(compareUris.last().endsWith("page=2"))
	}

	@Test
	fun marksExactlyThreeHundredChangedFilesAsConservativelyTruncated() {
		val files = (1..300).joinToString(",") {
			"""{"filename":"file-$it.kt","status":"modified","additions":1,"deletions":0,"patch":null}"""
		}
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				else -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""
					{
					  "status":"ahead",
					  "ahead_by":1,
					  "commits":[{
					    "sha":"commit-1",
					    "html_url":"https://github.com/acme/plot/commit/commit-1",
					    "author":{"login":"ada"},
					    "commit":{"message":"First","committer":{"date":"2026-01-01T00:00:00Z"}}
					  }],
					  "files":[$files]
					}
					""".trimIndent(),
				)
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val result = client.compareCommits(77, 44, "acme", "plot", "base", "head", 1)

		assertEquals(300, result.files.size)
		assertTrue(result.filesTruncated)
	}

	@Test
	fun rejectsAheadComparisonWhenGitHubOmitsExpectedCommits() {
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				else -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""{"status":"ahead","ahead_by":1,"commits":[],"files":[]}""",
				)
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val exception = assertFailsWith<ApiException> {
			client.compareCommits(77, 44, "acme", "plot", "base", "head", 1)
		}

		assertEquals("GITHUB_INVALID_RESPONSE", exception.error)
	}

	@Test
	fun rejectsDivergedComparisonWhenUniqueCommitCollectionIsPartial() {
		val duplicateCommit = """
			{
			  "sha":"commit-1",
			  "html_url":"https://github.com/acme/plot/commit/commit-1",
			  "author":{"login":"ada"},
			  "commit":{"message":"First","committer":{"date":"2026-01-01T00:00:00Z"}}
			}
		""".trimIndent()
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				else -> GitHubHttpResponse(
					200,
					emptyMap(),
					"""{"status":"diverged","ahead_by":2,"commits":[$duplicateCommit,$duplicateCommit],"files":[]}""",
				)
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val exception = assertFailsWith<ApiException> {
			client.compareCommits(77, 44, "acme", "plot", "base", "head", 1)
		}

		assertEquals("GITHUB_INVALID_RESPONSE", exception.error)
	}

	@Test
	fun returnsAssociatedPullRequestsDeduplicatedByProviderId() {
		var pullUri: String? = null
		val pull = """
			{"id":81,"number":8,"title":"Release","body":"Body","html_url":"https://github.com/acme/plot/pull/8",
			 "user":{"login":"ada"},"base":{"ref":"main"},"head":{"ref":"release"},
			 "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z","merged_at":"2026-01-02T00:00:00Z"}
		""".trimIndent()
		val transport = GitHubHttpTransport { _, uri, _, _ ->
			when {
				uri.path.endsWith("/access_tokens") -> GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				else -> {
					pullUri = uri.toString()
					GitHubHttpResponse(200, emptyMap(), "[$pull,$pull]")
				}
			}
		}
		val client = GitHubRestClient(properties(), objectMapper, transport = transport)

		val result = client.listPullRequestsForCommit(77, 44, "acme", "plot", "commit/sha")

		assertEquals(listOf(81L), result.map { it.id })
		assertTrue(pullUri!!.contains("/commits/commit%2Fsha/pulls?per_page=100&page=1"))
	}

	@Test
	fun classifiesRateLimitAndProviderFailuresAsRetryableCodes() {
		fun failure(response: GitHubHttpResponse): ApiException {
			val transport = GitHubHttpTransport { _, uri, _, _ ->
				if (uri.path.endsWith("/access_tokens")) {
					GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				} else {
					response
				}
			}
			return assertFailsWith {
				GitHubRestClient(properties(), objectMapper, transport = transport)
					.resolveTagCommit(77, 44, "acme", "plot", "v1")
			}
		}

		assertEquals(
			"GITHUB_RATE_LIMITED",
			failure(GitHubHttpResponse(403, mapOf("X-RateLimit-Remaining" to listOf("0")), "{}")).error,
		)
		assertEquals(
			"GITHUB_PROVIDER_UNAVAILABLE",
			failure(GitHubHttpResponse(503, emptyMap(), "provider secret")).error,
		)
	}

	@Test
	fun classifiesAccessLossNotFoundAndMalformedResponsesAsPermanentCodes() {
		fun failure(response: GitHubHttpResponse): ApiException {
			val transport = GitHubHttpTransport { _, uri, _, _ ->
				if (uri.path.endsWith("/access_tokens")) {
					GitHubHttpResponse(201, emptyMap(), """{"token":"installation-token"}""")
				} else {
					response
				}
			}
			return assertFailsWith {
				GitHubRestClient(properties(), objectMapper, transport = transport)
					.resolveTagCommit(77, 44, "acme", "plot", "v1")
			}
		}

		assertEquals("GITHUB_ACCESS_DENIED", failure(GitHubHttpResponse(403, emptyMap(), "{}")).error)
		assertEquals("GITHUB_NOT_FOUND", failure(GitHubHttpResponse(404, emptyMap(), "{}")).error)
		assertEquals("GITHUB_INVALID_RESPONSE", failure(GitHubHttpResponse(200, emptyMap(), "{")).error)
	}

	private fun properties(privateKey: String = testPrivateKey()) = GitHubProperties(
		enabled = true,
		appId = "123",
		appSlug = "plot",
		privateKey = privateKey,
		stateSecret = "state-secret",
		apiBaseUrl = "https://api.github.test",
	)

	private fun decodeJwtPart(value: String) = objectMapper.readTree(Base64.getUrlDecoder().decode(value))

	private fun testPrivateKey(keyPair: java.security.KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()): String {
		return "-----BEGIN PRIVATE KEY-----\n" +
			Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) +
			"\n-----END PRIVATE KEY-----"
	}
}
