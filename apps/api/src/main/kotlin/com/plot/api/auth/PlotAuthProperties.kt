package com.plot.api.auth

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.auth")
data class PlotAuthProperties(
	val enabled: Boolean = true,
	val issuer: String = "https://app.useplot.xyz",
	val audience: String = "plot-api",
	val jwksUri: String = "",
	val required: Boolean = true,
	val githubClientId: String = "",
	val githubClientSecret: String = "",
	val allowedEmails: Set<String> = emptySet(),
	val sessionCookieName: String = "plot.session",
	val sessionTtl: Duration = Duration.ofDays(30),
	val cookieDomain: String? = null,
	val appOrigin: String = "https://app.useplot.xyz",
	val apiOrigin: String = "http://127.0.0.1:8080",
	val jwtTtl: Duration = Duration.ofMinutes(15),
)
