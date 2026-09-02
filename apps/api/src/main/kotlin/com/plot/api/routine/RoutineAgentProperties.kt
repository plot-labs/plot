package com.plot.api.routine

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("plot.routine-agent")
data class RoutineAgentProperties(
	val workersEnabled: Boolean = true,
	val autoDispatchEnabled: Boolean = true,
	val claimTimeout: Duration = Duration.ofMinutes(2),
	val retryInitialDelay: Duration = Duration.ofSeconds(1),
	val maxAttempts: Int = 3,
	val maxModelCalls: Int = 8,
	val maxToolCalls: Int = 8,
	val maxRunDuration: Duration = Duration.ofMinutes(3),
	val maxInputCharacters: Int = 12_000,
	val maxEvidenceCharacters: Int = 60_000,
	val searchResultLimit: Int = 8,
) {
	init {
		require(!claimTimeout.isNegative && !claimTimeout.isZero) { "plot.routine-agent.claim-timeout must be positive" }
		require(!retryInitialDelay.isNegative) { "plot.routine-agent.retry-initial-delay must not be negative" }
		require(maxAttempts > 0) { "plot.routine-agent.max-attempts must be positive" }
		require(maxModelCalls > 0 && maxToolCalls > 0) { "plot.routine-agent call budgets must be positive" }
		require(!maxRunDuration.isNegative && !maxRunDuration.isZero) { "plot.routine-agent.max-run-duration must be positive" }
		require(maxInputCharacters > 0 && maxEvidenceCharacters >= maxInputCharacters) {
			"plot.routine-agent evidence budgets are invalid"
		}
		require(searchResultLimit in 1..25) { "plot.routine-agent.search-result-limit must be between 1 and 25" }
	}
}
