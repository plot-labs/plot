package com.plot.api.github

import com.plot.api.common.WorkerExecutors
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ScheduledExecutorService

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class GitHubReleaseAutomationConfiguration {
	@Bean
	fun githubReleaseTaskExecutor(): ThreadPoolTaskExecutor =
		WorkerExecutors.singleSlotTaskExecutor("plot-github-release-")

	@Bean(destroyMethod = "shutdown")
	fun githubReleaseHeartbeatExecutor(): ScheduledExecutorService =
		WorkerExecutors.daemonScheduler("plot-github-release-heartbeat")

	@Bean
	fun githubReleaseDraftDispatcher(
		@Qualifier("githubReleaseTaskExecutor") taskExecutor: TaskExecutor,
		@Qualifier("githubWorkerRetryExecutor") retryExecutor: ScheduledExecutorService,
		worker: GitHubReleaseDraftWorker,
		properties: GitHubProperties,
	): GitHubReleaseDraftDispatcher = DefaultGitHubReleaseDraftDispatcher(
		taskExecutor,
		retryExecutor,
		worker,
		properties,
	)
}
