package com.plot.api.common

import com.plot.api.auth.PlotAuthProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.authentication.BadCredentialsException

@Configuration
@EnableWebSecurity
class SecurityConfig(
	private val authProperties: PlotAuthProperties,
	@Value("\${plot.dev-bootstrap.enabled:false}") private val devBootstrapEnabled: Boolean,
	private val environment: Environment,
) {
	@Bean
	fun jwtDecoder(): JwtDecoder {
		val uri = authProperties.jwksUri.trim()
		if (uri.isBlank()) return JwtDecoder { throw BadCredentialsException("JWT JWKS is not configured") }
		val decoder = NimbusJwtDecoder.withJwkSetUri(uri)
			.jwsAlgorithm(SignatureAlgorithm.ES256)
			.build()
		decoder.setJwtValidator(DelegatingOAuth2TokenValidator(
			JwtValidators.createDefaultWithIssuer(authProperties.issuer),
			AudienceValidator(authProperties.audience),
		))
		return decoder
	}

	@Bean
	fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
		val legacyDevMode = devBootstrapEnabled || environment.activeProfiles.any { it in setOf("local", "dev", "test", "generation-certification") }
		val enforce = authProperties.enabled && authProperties.required && !legacyDevMode
		if (enforce) {
			http
				.csrf { it.ignoringRequestMatchers("/api/**") }
				.authorizeHttpRequests { requests ->
					requests.requestMatchers("/actuator/health", "/api/auth/**").permitAll()
						.requestMatchers("/api/account/bootstrap", "/api/me").authenticated()
						.anyRequest().authenticated()
				}
				.oauth2ResourceServer { it.jwt {} }
		} else {
			http
				.csrf { it.disable() }
				.authorizeHttpRequests { requests -> requests.anyRequest().permitAll() }
		}
		return http.build()
	}
}

private class AudienceValidator(private val audience: String) : OAuth2TokenValidator<Jwt> {
	private val error = OAuth2Error("invalid_token", "The required audience is missing", null)

	override fun validate(token: Jwt): OAuth2TokenValidatorResult = if (token.audience.contains(audience)) {
		OAuth2TokenValidatorResult.success()
	} else {
		OAuth2TokenValidatorResult.failure(error)
	}
}
