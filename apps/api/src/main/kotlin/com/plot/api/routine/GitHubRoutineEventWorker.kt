package com.plot.api.routine

import org.springframework.stereotype.Component

/**
 * Compatibility adapter for the existing GitHub poller.  V24 retires the
 * V21 event claim lifecycle; canonical RoutineExecutions are drained by the
 * shared RoutineWorker instead.
 */
@Component
class GitHubRoutineEventWorker(
	private val routineWorker: RoutineWorker,
) {
	fun drain(): Int = if (routineWorker.drain()) 1 else 0
}
