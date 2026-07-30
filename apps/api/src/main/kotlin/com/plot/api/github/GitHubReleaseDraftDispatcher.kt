package com.plot.api.github

import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException

fun interface GitHubReleaseDraftDispatcher {
	fun dispatch()
}

class DefaultGitHubReleaseDraftDispatcher(
	private val taskExecutor: TaskExecutor,
	private val worker: GitHubReleaseDraftWorker,
) : GitHubReleaseDraftDispatcher {
	override fun dispatch() {
		try {
			taskExecutor.execute {
				worker.drain()
			}
		} catch (_: TaskRejectedException) {
			// The single-thread worker already has a turn running or queued.
		}
	}
}
