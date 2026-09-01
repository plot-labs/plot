package com.plot.api.routine

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
	name = ["plot.routines.startup-recovery-enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class RoutineWorkerStartupRecovery(
	private val agentProperties: RoutineAgentProperties,
	private val routineRunDispatcher: RoutineRunDispatcher,
	private val agentRunDispatcher: AgentRunDispatcher,
	private val scheduleScanner: RoutineScheduleScanner,
) {
	@EventListener(ApplicationReadyEvent::class)
	fun recoverOrphanedWork() {
		if (!agentProperties.workersEnabled) return
		routineRunDispatcher.dispatch()
		if (scheduleScanner.scanDue()) {
			routineRunDispatcher.dispatch()
		}
		agentRunDispatcher.dispatch()
	}
}
