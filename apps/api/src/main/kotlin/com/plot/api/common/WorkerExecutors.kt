package com.plot.api.common

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Executor shapes shared by every durable worker queue.
 *
 * Each queue runs one turn at a time and lets at most one more turn wait, so a
 * burst of wakeups collapses into a single follow-up turn instead of piling up.
 */
internal object WorkerExecutors {
	/** One running turn plus one queued turn; in-flight work finishes on shutdown. */
	fun singleSlotTaskExecutor(threadNamePrefix: String): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix(threadNamePrefix)
		setStrictEarlyShutdown(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}

	/** Daemon timer thread for retry wakeups and lease heartbeats. */
	fun daemonScheduler(threadName: String): ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor { task ->
			Thread(task, threadName).apply { isDaemon = true }
		}
}
