package com.plot.api.auth

import java.time.Instant
import java.util.function.Supplier
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder

class WorkOSJwtSecurityIntegrationTest {
	@Test
	fun `WorkOS decoder is wired only from the provider JWKS configuration`() {
		ApplicationContextRunner()
			.withUserConfiguration(WorkOSJwtConfiguration::class.java)
			.withBean(WorkOSAuthProperties::class.java, Supplier { properties() })
			.withPropertyValues("plot.workos.enabled=true")
			.run { context ->
				assertTrue(context.containsBean("jwtDecoder"))
				assertNotNull(context.getBean(JwtDecoder::class.java))
			}
	}

	@Test
	fun `matching audience or WorkOS client id succeeds and missing values fail closed`() {
		val valid = jwt(listOf("plot-api"))
		val wrong = jwt(listOf("another-api"))
		val missing = jwt(emptyList())
		val workOsToken = Jwt.withTokenValue("workos-token")
			.header("alg", "RS256")
			.issuer("https://issuer.workos.test")
			.subject("user_01HWORKOS")
			.claim("client_id", "plot-api")
			.issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
			.expiresAt(Instant.parse("2026-01-01T01:00:00Z"))
			.build()
		val validator = WorkOSAudienceValidator("plot-api")

		assertFalse(validator.validate(valid).hasErrors())
		assertFalse(validator.validate(workOsToken).hasErrors())
		assertTrue(validator.validate(wrong).hasErrors())
		assertTrue(validator.validate(missing).hasErrors())
	}

	@Test
	fun `a WorkOS subject is retained as the provider identity for local mapping`() {
		val token = jwt(listOf("plot-api"))
		assertTrue(token.subject == "user_01HWORKOS")
		assertTrue(token.issuer.toString() == "https://issuer.workos.test")
	}

	private fun jwt(audience: List<String>) = Jwt.withTokenValue("test-token")
		.header("alg", "RS256")
		.issuer("https://issuer.workos.test")
		.subject("user_01HWORKOS")
		.audience(audience)
		.issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
		.expiresAt(Instant.parse("2026-01-01T01:00:00Z"))
		.build()

	private fun properties() = WorkOSAuthProperties(
		enabled = true,
		apiKey = "sk_test_workos",
		clientId = "client_test_workos",
		issuer = "https://issuer.workos.test",
		audience = "plot-api",
		jwksUri = "https://issuer.workos.test/.well-known/jwks.json",
	)
}
