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
	fun creditConfigurationRejectsNonPositiveValues() {
		assertFailsWith<IllegalArgumentException> {
			configured(trialCredits = 0)
		}
		assertFailsWith<IllegalArgumentException> {
			configured(organizationId = null)
		}
		assertFailsWith<IllegalArgumentException> {
			configured(requestTimeout = Duration.ZERO)
		}
	}

	@Test
	fun webhookCanRemainEnabledWithoutCreditApiCredentials() {
		PolarProperties(enabled = true, webhookSecret = "secret")
	}

	private fun configured(
		trialCredits: Long = 5_000,
		organizationId: String? = "polar_org",
		requestTimeout: Duration = Duration.ofSeconds(5),
	) = PolarProperties(
		creditsEnabled = true,
		accessToken = "polar_test_token",
		organizationId = organizationId,
		aiMeterId = "meter_ai",
		trialCredits = trialCredits,
		trialPolicyVersion = "trial-v1",
		requestTimeout = requestTimeout,
	)
}
