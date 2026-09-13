package com.plot.api.common

import com.plot.api.auth.WorkOSAuthProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig(
	private val workOSProperties: WorkOSAuthProperties,
	private val environment: Environment,
) {
	@Bean
	fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
		val developmentBypass = environment.allowsDevelopmentAuthBypass()
		val enforceWorkOS = workOSProperties.enabled && !developmentBypass
		http
			.csrf { it.disable() }
			.authorizeHttpRequests { requests ->
				requests.requestMatchers("/actuator/health", "/api/polar/webhook", "/api/workos/webhook").permitAll()
					.requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
					.requestMatchers(HttpMethod.POST, "/api/public/waitlist").permitAll()
					.requestMatchers(HttpMethod.POST, "/api/github/webhook").permitAll()
				if (enforceWorkOS) requests.anyRequest().authenticated()
				else requests.anyRequest().permitAll()
		}
		if (enforceWorkOS) http.oauth2ResourceServer { it.jwt {} }
		return http.build()
	}
}

internal fun Environment.allowsDevelopmentAuthBypass(): Boolean =
	activeProfiles.any { it in setOf("local", "test") }
