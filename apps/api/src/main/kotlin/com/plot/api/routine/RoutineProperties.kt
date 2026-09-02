package com.plot.api.routine

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.routines")
data class RoutineProperties(
	val scheduleScanDelay: Duration = Duration.ofHours(1),
) {
	init {
		require(!scheduleScanDelay.isNegative && !scheduleScanDelay.isZero) {
			"plot.routines.schedule-scan-delay must be positive"
		}
	}
}
