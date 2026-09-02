package com.plot.api.common

import com.plot.api.auth.PlotAuthProperties
import com.plot.api.auth.jwt.PlotJwtService
import com.plot.api.auth.session.SessionAuthenticationFilter
import com.plot.api.auth.session.AuthSessionService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
	private val authProperties: PlotAuthProperties,
	private val environment: Environment,
	private val plotJwtService: PlotJwtService,
	private val authSessionService: AuthSessionService,
) {
	@Bean
	fun jwtDecoder(): JwtDecoder = plotJwtService.decoder()

	@Bean
	fun sessionAuthenticationFilter(): SessionAuthenticationFilter = SessionAuthenticationFilter(authSessionService)

	@Bean
	fun securityFilterChain(
		http: HttpSecurity,
		sessionAuthenticationFilter: SessionAuthenticationFilter,
	): SecurityFilterChain {
		val enforce = authProperties.enabled && authProperties.required && !environment.allowsDevelopmentAuthBypass()
		if (enforce) {
			http
				.csrf { it.ignoringRequestMatchers("/api/**") }
				.addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
				.authorizeHttpRequests { requests ->
					requests.requestMatchers("/actuator/health", "/api/polar/webhook").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/github/webhook").permitAll()
						.requestMatchers("/api/auth/sign-in/**", "/api/auth/callback/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/auth/jwks").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/sign-out").permitAll()
						.requestMatchers("/api/auth/session", "/api/auth/token").authenticated()
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

internal fun Environment.allowsDevelopmentAuthBypass(): Boolean =
	activeProfiles.any { it in setOf("local", "test") }
