package com.plot.api.billing

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.polar")
data class PolarProperties(
	val enabled: Boolean = false,
	val webhookSecret: String? = null,
	val timestampToleranceSeconds: Long = 300,
) {
	init {
		require(timestampToleranceSeconds > 0) {
			"plot.polar.timestamp-tolerance-seconds must be positive"
		}
	}
}
