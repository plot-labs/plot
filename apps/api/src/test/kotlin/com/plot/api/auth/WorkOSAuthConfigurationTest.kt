package com.plot.api.auth

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorkOSAuthConfigurationTest {
	@Test
	fun disabledConfigurationAllowsLocalTestDefaults() {
		val properties = WorkOSAuthProperties()

		assertTrue(!properties.enabled)
		assertEquals("owner", properties.ownerRoleSlug)
	}

	@Test
	fun enabledConfigurationRequiresProviderValues() {
		val failure = assertFailsWith<IllegalArgumentException> {
			WorkOSAuthProperties(enabled = true)
		}

		assertTrue(failure.message.orEmpty().contains("api-key"))
	}

	@Test
	fun enabledConfigurationRejectsNonLoopbackHttpOrigin() {
		val failure = assertFailsWith<IllegalArgumentException> {
			WorkOSAuthProperties(
				enabled = true,
				apiKey = "sk_test",
				clientId = "client_test",
				issuer = "http://issuer.example",
				audience = "plot-api",
				jwksUri = "https://api.workos.com/jwks",
			)
		}

		assertTrue(failure.message.orEmpty().contains("issuer"))
	}
}
