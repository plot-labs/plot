package com.plot.api.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder

/**
 * The API consumes WorkOS access tokens but never creates or signs account
 * credentials. Nimbus owns key discovery and refresh through the configured
 * WorkOS JWKS endpoint, so a rotated provider key can be accepted without a
 * Plot database migration or a local signing-key table.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "plot.workos", name = ["enabled"], havingValue = "true")
class WorkOSJwtConfiguration(
	private val properties: WorkOSAuthProperties,
) {
	@Bean
	fun jwtDecoder(): JwtDecoder {
		val decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri).build()
		decoder.setJwtValidator(
			DelegatingOAuth2TokenValidator(
				JwtValidators.createDefaultWithIssuer(properties.issuer),
				WorkOSAudienceValidator(properties.audience),
			),
		)
		return decoder
	}
}

internal class WorkOSAudienceValidator(
	private val audience: String,
) : OAuth2TokenValidator<Jwt> {
	private val error = OAuth2Error("invalid_token", "The required WorkOS audience is missing", null)

	override fun validate(token: Jwt): OAuth2TokenValidatorResult {
		// WorkOS AuthKit session tokens identify the application with `client_id`.
		// Keep accepting a standard `aud` claim for generic JWT fixtures and tokens,
		// but never dereference a missing audience claim.
		val matchesAudience = token.audience.orEmpty().contains(audience)
		val matchesClientId = token.getClaimAsString("client_id") == audience
		return if (matchesAudience || matchesClientId) {
		OAuth2TokenValidatorResult.success()
	} else {
		OAuth2TokenValidatorResult.failure(error)
	}
	}
}
