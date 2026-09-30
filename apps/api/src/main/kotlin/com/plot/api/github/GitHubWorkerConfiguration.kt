package com.plot.api.github

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class GitHubWorkerConfiguration {
	@Bean
	fun githubSignalTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix("plot-github-signal-")
		setStrictEarlyShutdown(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}

	@Bean(destroyMethod = "shutdown")
	fun githubWorkerRetryExecutor(): ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "plot-github-worker-retry").apply { isDaemon = true }
		}
}
