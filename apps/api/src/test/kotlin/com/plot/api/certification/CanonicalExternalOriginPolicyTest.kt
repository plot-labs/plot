package com.plot.api.certification

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CanonicalExternalOriginPolicyTest {
	private val policy = CanonicalExternalOriginPolicy()

	@Test
	fun `only exact canonical credential origins are accepted`() {
		assertEquals(URI("https://openrouter.ai/api/v1"), policy.requireOpenRouterApi("https://openrouter.ai/api/v1"))
		assertEquals(URI("https://api.github.com"), policy.requireGitHubApi("https://api.github.com"))

		listOf(
			"http://openrouter.ai/api/v1",
			"https://openrouter.ai:443/api/v1",
			"https://user@openrouter.ai/api/v1",
			"https://openrouter.ai/api/v1/",
			"https://openrouter.ai/api/v1?route=other",
			"https://OPENROUTER.ai/api/v1",
			"https://openrouter.ai.evil.example/api/v1",
		).forEach { unsafe ->
			val failure = assertFailsWith<CertificationPreflightException> { policy.requireOpenRouterApi(unsafe) }
			assertEquals(CertificationFailureCode.NON_CANONICAL_ORIGIN, failure.code)
			assertFalse(failure.message.orEmpty().contains(unsafe))
		}

		listOf(
			"https://github.com",
			"https://api.github.com:443",
			"https://token@api.github.com",
			"https://github.example.test/api/v3",
		).forEach { unsafe ->
			assertFailsWith<CertificationPreflightException> { policy.requireGitHubApi(unsafe) }
		}
	}

	@Test
	fun `redirect targets are rejected before credentials can be forwarded`() {
		listOf(
			"https://openrouter.ai/api/v1/models",
			"https://api.openrouter.ai/api/v1",
			"https://evil.example",
		).forEach { location ->
			val failure = assertFailsWith<CertificationPreflightException> {
				policy.rejectRedirect(location)
			}
			assertEquals(CertificationFailureCode.REDIRECT_REJECTED, failure.code)
			assertFalse(failure.message.orEmpty().contains(location))
		}
	}

	@Test
	fun `child environment is an explicit per-provider allow list`() {
		val source = mapOf(
			"PATH" to "/usr/bin",
			"JAVA_HOME" to "/safe/java",
			"LANG" to "ko_KR.UTF-8",
			"OPENROUTER_API_KEY" to "openrouter-secret",
			"GITHUB_TOKEN" to "github-secret",
			"AWS_SECRET_ACCESS_KEY" to "must-not-inherit",
			"PLOT_PRIVATE_SOURCE" to "must-not-inherit",
		)

		val openRouter = CertificationEnvironmentPolicy.openRouterPreflight(source)
		assertEquals(setOf("PATH", "JAVA_HOME", "LANG", "OPENROUTER_API_KEY"), openRouter.keys)
		assertFalse(openRouter.values.contains("github-secret"))
		assertFalse(openRouter.values.contains("must-not-inherit"))
		assertFalse(openRouter.toString().contains("openrouter-secret"))

		val github = CertificationEnvironmentPolicy.githubPreflight(source)
		assertEquals(setOf("PATH", "JAVA_HOME", "LANG", "GITHUB_TOKEN"), github.keys)
		assertFalse(github.values.contains("openrouter-secret"))
		assertFalse(github.toString().contains("github-secret"))

		val missing = assertFailsWith<CertificationPreflightException> {
			CertificationEnvironmentPolicy.openRouterPreflight(source - "OPENROUTER_API_KEY")
		}
		assertEquals(CertificationFailureCode.MISSING_CREDENTIAL, missing.code)
		assertFalse(missing.message.orEmpty().contains("openrouter-secret"))
	}
}
