package com.plot.api.github

import java.util.concurrent.ScheduledExecutorService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component

@Component
class GitHubRepositoryMonitoringDispatcher(
	@Qualifier("githubRepositoryMonitoringTaskExecutor") taskExecutor: TaskExecutor,
	@Qualifier("githubWorkerRetryExecutor") retryExecutor: ScheduledExecutorService,
	worker: GitHubRepositoryMonitoringWorker,
) {
	private val delegate = GitHubWorkerDispatch(
		taskExecutor = taskExecutor,
		retryExecutor = retryExecutor,
		recover = worker::recover,
		drain = worker::drain,
		earliestRetryAt = worker::nextRetryAt,
	)

	fun dispatch() = delegate.dispatch()
}

@Configuration(proxyBeanMethods = false)
class GitHubRepositoryMonitoringConfiguration {
	@Bean
	fun githubRepositoryMonitoringAnalyzer() = GitHubReleaseConventionAnalyzer()

	@Bean
	fun githubRepositoryMonitoringTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix("plot-github-monitoring-")
		setStrictEarlyShutdown(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}

}
