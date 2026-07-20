package com.plot.api.github

import com.plot.api.common.ApiException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class GitHubGuardTest {
	@Test
	fun configuredIntegrationRequiresExplicitLocalProfileAndLoopback() {
		val properties = configuredProperties()
		val missingProfile = MockEnvironment().withProperty("server.address", "127.0.0.1")
		val exception = assertFailsWith<ApiException> { GitHubGuard(properties, missingProfile).requireEnabled() }
		assertEquals("GITHUB_DEV_PROFILE_REQUIRED", exception.error)

		val local = MockEnvironment().apply {
			setActiveProfiles("local")
			setProperty("server.address", "127.0.0.1")
		}
		GitHubGuard(properties, local).requireEnabled()

		val publicAddress = MockEnvironment().apply {
			setActiveProfiles("local")
			setProperty("server.address", "0.0.0.0")
		}
		val exposureException = assertFailsWith<ApiException> { GitHubGuard(properties, publicAddress).requireEnabled() }
		assertEquals("GITHUB_DEV_EXPOSURE_INVALID", exposureException.error)
	}

	@Test
	fun missingSecretsFailWithoutChangingUnrelatedStartup() {
		val properties = GitHubProperties(enabled = false)
		val environment = MockEnvironment().apply {
			setActiveProfiles("local")
			setProperty("server.address", "127.0.0.1")
		}
		val exception = assertFailsWith<ApiException> { GitHubGuard(properties, environment).requireEnabled() }
		assertEquals("GITHUB_NOT_CONFIGURED", exception.error)
		assertNotNull(exception.message)
	}

	@Test
	fun certificationProfileAllowsPersistedReadWithoutProviderCredentials() {
		val properties = GitHubProperties(enabled = false)
		val environment = MockEnvironment().apply {
			setActiveProfiles("generation-certification")
			setProperty("server.address", "127.0.0.1")
		}
		val guard = GitHubGuard(properties, environment)

		guard.requireReadAccess()

		val exception = assertFailsWith<ApiException> { guard.requireEnabled() }
		assertEquals("GITHUB_NOT_CONFIGURED", exception.error)
	}

	private fun configuredProperties() = GitHubProperties(
		enabled = true,
		appId = "1",
		appSlug = "plot",
		privateKey = "pem",
		stateSecret = "state-secret",
	)
}
