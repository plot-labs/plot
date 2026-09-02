package com.plot.api.auth.policy

import com.plot.api.auth.PlotAuthProperties
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class AllowedEmailPolicyTest {
	private val environment = MockEnvironment().apply { setActiveProfiles("test") }

	@Test
	fun normalizesAndMatchesAllowedEmails() {
		val policy = AllowedEmailPolicy(
			PlotAuthProperties(allowedEmails = setOf("member@example.com")),
			environment,
		)

		assertTrue(policy.isAllowed(" Member@Example.com "))
		assertFalse(policy.isAllowed("other@example.com"))
	}

	@Test
	fun normalizesConfiguredAllowlistEntries() {
		val policy = AllowedEmailPolicy(
			PlotAuthProperties(allowedEmails = setOf("Member@Example.com")),
			environment,
		)

		assertTrue(policy.isAllowed("member@example.com"))
	}
}
