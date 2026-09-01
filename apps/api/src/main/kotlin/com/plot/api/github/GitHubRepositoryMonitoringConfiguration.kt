package com.plot.api.github

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component

@Component
class GitHubRepositoryMonitoringDispatcher(
	@Qualifier("githubRepositoryMonitoringTaskExecutor") private val taskExecutor: TaskExecutor,
	private val worker: GitHubRepositoryMonitoringWorker,
) {
	fun dispatch() {
		GitHubWorkerDispatch.dispatchQueued(
			taskExecutor = taskExecutor,
			redispatch = ::dispatch,
			recover = worker::recover,
			drain = worker::drain,
		)
	}
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
