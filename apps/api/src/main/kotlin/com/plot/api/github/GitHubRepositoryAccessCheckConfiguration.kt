package com.plot.api.github

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component

@Configuration(proxyBeanMethods = false)
class GitHubRepositoryAccessCheckConfiguration {
	@Bean
	fun githubRepositoryAccessCheckTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix("plot-github-access-check-")
		setStrictEarlyShutdown(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}
}

@Component
class GitHubRepositoryAccessCheckDispatcher(
	@Qualifier("githubRepositoryAccessCheckTaskExecutor") private val taskExecutor: TaskExecutor,
	private val worker: GitHubRepositoryAccessCheckWorker,
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
