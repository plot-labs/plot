package com.plot.api.github

import java.util.concurrent.ScheduledExecutorService
import org.springframework.core.task.TaskExecutor

fun interface GitHubReleaseDraftDispatcher {
	fun dispatch()
}

class DefaultGitHubReleaseDraftDispatcher(
	taskExecutor: TaskExecutor,
	retryExecutor: ScheduledExecutorService?,
	worker: GitHubReleaseDraftWorker,
) : GitHubReleaseDraftDispatcher {
	private val delegate = GitHubWorkerDispatch(
		taskExecutor = taskExecutor,
		retryExecutor = retryExecutor,
		recover = worker::recover,
		drain = worker::drain,
		earliestRetryAt = worker::nextRetryAt,
		onQueueEmpty = worker::reconcile,
	)

	override fun dispatch() = delegate.dispatch()
}
