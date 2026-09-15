package com.plot.api.agent

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class AgentConfiguration {
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
