package com.plot.api.github

import com.plot.api.common.ApiException
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class GitHubRestClientTest {
	private val objectMapper = ObjectMapper()

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
		assertEquals("RS256", decodeJwtPart(parts[0]).path("alg").textValue())
		val claims = decodeJwtPart(parts[1])
		assertEquals(now.epochSecond - 60, claims.path("iat").longValue())
		assertEquals(now.epochSecond + 540, claims.path("exp").longValue())
		assertEquals("123", claims.path("iss").textValue())
		assertTrue(Signature.getInstance("SHA256withRSA").run {
			initVerify(keyPair.public)
			update("${parts[0]}.${parts[1]}".toByteArray())
			verify(Base64.getUrlDecoder().decode(parts[2]))
		})
		val request = objectMapper.readTree(tokenBody!!)
		assertEquals(listOf(44L), request.path("repository_ids").toList().map { it.longValue() })
		assertEquals("read", request.path("permissions").path("metadata").textValue())
		assertEquals("read", request.path("permissions").path("pull_requests").textValue())
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
		assertEquals("read", request.path("permissions").path("metadata").textValue())
		assertEquals("read", request.path("permissions").path("pull_requests").textValue())
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
		)
		val client = GitHubRestClient(properties, ObjectMapper(), Clock.fixed(Instant.parse("2026-01-10T00:00:00Z"), ZoneOffset.UTC), transport)

		val result = client.listClosedPullRequests(77, 44, "acme", "plot", 3)

		assertEquals(listOf(11L, 12L), result.map { it.id })
		assertEquals(3, calls.size)
		assertTrue(calls.all { it.third["Authorization"] == "Bearer installation-token" || it.third["Authorization"]?.startsWith("Bearer ey") == true })
		assertTrue(calls.last().second.contains("page=2"))
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
