package com.plot.api.billing

import java.time.Duration
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class PolarPropertiesTest {
	@Test
	fun creditsRequireAllServerSideConfiguration() {
		assertFailsWith<IllegalArgumentException> {
			PolarProperties(creditsEnabled = true)
		}
	}

	@Test
	fun creditConfigurationRejectsNonPositiveTimeout() {
		assertFailsWith<IllegalArgumentException> {
			configured(requestTimeout = Duration.ZERO)
		}
	}

	@Test
	fun webhookCanRemainEnabledWithoutCreditApiCredentials() {
		PolarProperties(enabled = true, webhookSecret = "secret")
	}

	private fun configured(
		requestTimeout: Duration = Duration.ofSeconds(5),
	) = PolarProperties(
		creditsEnabled = true,
		accessToken = "polar_test_token",
		aiMeterId = "meter_ai",
		requestTimeout = requestTimeout,
	)
}
