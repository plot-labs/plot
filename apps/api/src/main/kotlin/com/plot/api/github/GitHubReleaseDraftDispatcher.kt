package com.plot.api.github

import org.springframework.core.task.TaskExecutor

fun interface GitHubReleaseDraftDispatcher {
	fun dispatch()
}

class DefaultGitHubReleaseDraftDispatcher(
	private val taskExecutor: TaskExecutor,
	private val worker: GitHubReleaseDraftWorker,
) : GitHubReleaseDraftDispatcher {
	override fun dispatch() {
		GitHubWorkerDispatch.dispatchQueued(
			taskExecutor = taskExecutor,
			redispatch = ::dispatch,
			recover = worker::recover,
			drain = worker::drain,
			onQueueEmpty = worker::reconcile,
		)
	}
}
