package com.plot.api.billing

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.polar")
data class PolarProperties(
	val enabled: Boolean = false,
	val webhookSecret: String? = null,
	val timestampToleranceSeconds: Long = 300,
	val creditsEnabled: Boolean = false,
	val accessToken: String? = null,
	val apiBaseUrl: String = "https://api.polar.sh",
	val aiMeterId: String? = null,
	val creditProductId: String? = null,
	val subscriptionProductId: String? = null,
	val checkoutSuccessUrl: String? = null,
	val checkoutReturnUrl: String? = null,
	val trialCredits: Long = 5_000,
	val trialPolicyVersion: String = "trial-v1",
	val requestTimeout: Duration = Duration.ofSeconds(10),
	val maxResponseBytes: Int = 256 * 1024,
) {
	init {
			require(timestampToleranceSeconds > 0) {
			"plot.polar.timestamp-tolerance-seconds must be positive"
		}
		require(!requestTimeout.isNegative && !requestTimeout.isZero) {
			"plot.polar.request-timeout must be positive"
		}
		require(maxResponseBytes > 0) { "plot.polar.max-response-bytes must be positive" }
		require(trialCredits > 0) { "plot.polar.trial-credits must be positive" }
		require(trialPolicyVersion.isNotBlank()) { "plot.polar.trial-policy-version must not be blank" }
		if (creditsEnabled) {
			require(!accessToken.isNullOrBlank()) { "plot.polar.access-token is required when Polar credits are enabled" }
			require(!aiMeterId.isNullOrBlank()) { "plot.polar.ai-meter-id is required when Polar credits are enabled" }
			require(apiBaseUrl.startsWith("https://")) { "plot.polar.api-base-url must use HTTPS" }
		}
	}
}
