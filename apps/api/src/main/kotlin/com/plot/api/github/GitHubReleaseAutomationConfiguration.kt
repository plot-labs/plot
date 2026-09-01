package com.plot.api.github

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class GitHubReleaseAutomationConfiguration {
	@Bean
	fun githubReleaseTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix("plot-github-release-")
		setStrictEarlyShutdown(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}

	@Bean(destroyMethod = "shutdown")
	fun githubReleaseHeartbeatExecutor(): ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "plot-github-release-heartbeat").apply { isDaemon = true }
		}

	@Bean
	fun githubReleaseDraftDispatcher(
		@Qualifier("githubReleaseTaskExecutor") taskExecutor: TaskExecutor,
		worker: GitHubReleaseDraftWorker,
	): GitHubReleaseDraftDispatcher = DefaultGitHubReleaseDraftDispatcher(taskExecutor, worker)
}
