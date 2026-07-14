package com.plot.api.generation

import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GenerationRunDispatcher(
	private val taskExecutor: TaskExecutor,
	private val drainBatch: () -> Boolean,
) {
	fun dispatch() {
		try {
			taskExecutor.execute {
				try {
					while (drainBatch()) {
						// Continue in bounded batches until no runnable checkpoint remains.
					}
				} catch (_: RuntimeException) {
					CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(::dispatch)
				}
			}
		} catch (_: TaskRejectedException) {
			// A running or queued drain consumes every currently runnable checkpoint.
		}
	}
}
