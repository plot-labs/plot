package com.plot.api.github

import com.plot.api.common.WorkerExecutors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class GitHubWorkerConfiguration {
	@Bean
	fun githubSignalTaskExecutor(): ThreadPoolTaskExecutor =
		WorkerExecutors.singleSlotTaskExecutor("plot-github-signal-")

	@Bean(destroyMethod = "shutdown")
	fun githubWorkerRetryExecutor(): ScheduledExecutorService =
		WorkerExecutors.daemonScheduler("plot-github-worker-retry")
}
