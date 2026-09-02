package com.plot.api.routine

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RoutineConfiguration {
	@Bean
	fun routineTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix("plot-routine-")
		setStrictEarlyShutdown(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}

	@Bean(destroyMethod = "shutdown")
	fun routineRetryExecutor(): ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "plot-routine-retry").apply { isDaemon = true }
		}

	@Bean
	fun agentRunTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix("plot-agent-run-")
		setStrictEarlyShutdown(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}

	@Bean(destroyMethod = "shutdown")
	fun agentRunRetryExecutor(): ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "plot-agent-run-retry").apply { isDaemon = true }
		}
}
