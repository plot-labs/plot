package com.plot.api.routine

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class RoutineScheduleScanner(
	private val worker: RoutineWorker,
	private val dispatcher: RoutineRunDispatcher,
	private val agentProperties: RoutineAgentProperties,
) {
	@Scheduled(fixedDelayString = "\${plot.routines.schedule-scan-delay:PT1H}")
	fun scan() {
		if (!agentProperties.workersEnabled) return
		if (claimAllScheduledDue() > 0) {
			dispatcher.dispatch()
		}
	}

	fun scanDue(): Boolean {
		if (!agentProperties.workersEnabled) return false
		return claimAllScheduledDue() > 0
	}

	private fun claimAllScheduledDue(): Int {
		var claimed = 0
		while (worker.claimScheduledDue()) {
			claimed++
		}
		return claimed
	}
}
