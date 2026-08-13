package com.plot.api.artifact.workflow

import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException

class ArtifactWorkflowRunDispatcher(
	private val taskExecutor: TaskExecutor,
	private val enabled: Boolean = true,
	private val drainBatch: () -> Boolean,
) {
	fun dispatch() {
		if (!enabled) return
		try {
			taskExecutor.execute {
				while (drainBatch()) {
					// Continue in bounded batches until no runnable checkpoint remains.
				}
			}
		} catch (_: TaskRejectedException) {
			// A running or queued drain consumes every currently runnable checkpoint.
		}
	}
}
