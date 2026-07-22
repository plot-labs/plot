package com.plot.api.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.auth")
data class PlotAuthProperties(
	val enabled: Boolean = true,
	val issuer: String = "https://app.useplot.xyz",
	val audience: String = "plot-api",
	val jwksUri: String = "",
	val required: Boolean = true,
)
