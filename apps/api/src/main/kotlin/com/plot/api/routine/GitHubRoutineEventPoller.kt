package com.plot.api.routine

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class GitHubRoutineEventPoller(
	private val worker: GitHubRoutineEventWorker,
) {
	@Scheduled(fixedDelayString = "\${plot.routines.github-event-poll-delay:PT5S}")
	fun poll() {
		repeat(MAX_DRAIN_BATCH) {
			if (worker.drain() == 0) return
		}
	}

	private companion object {
		// ponytail: bound shared-scheduler work; raise only when backlog metrics justify it.
		const val MAX_DRAIN_BATCH = 20
	}
}
