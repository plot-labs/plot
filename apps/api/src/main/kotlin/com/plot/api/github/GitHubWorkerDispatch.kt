package com.plot.api.github

import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException

internal object GitHubWorkerDispatch {
	fun dispatchQueued(
		taskExecutor: TaskExecutor,
		redispatch: () -> Unit,
		recover: () -> Int,
		drain: () -> Int,
		onQueueEmpty: () -> Unit = {},
	) {
		try {
			taskExecutor.execute {
				recover()
				if (drain() > 0) {
					redispatch()
				} else {
					onQueueEmpty()
				}
			}
		} catch (_: TaskRejectedException) {
			// A worker turn is already running or queued; it will drain remaining work.
		}
	}
}
