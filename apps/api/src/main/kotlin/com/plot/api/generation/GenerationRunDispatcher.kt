package com.plot.api.generation

import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException

class GenerationRunDispatcher(
	private val taskExecutor: TaskExecutor,
	private val drainBatch: () -> Boolean,
) {
	fun dispatch() {
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
