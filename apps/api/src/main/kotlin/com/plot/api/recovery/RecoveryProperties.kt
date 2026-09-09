package com.plot.api.recovery

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.recovery")
data class RecoveryProperties(
	val enabled: Boolean = false,
	val observeOnly: Boolean = false,
	val interval: Duration = Duration.ofSeconds(30),
	val batchSize: Int = 50,
)
