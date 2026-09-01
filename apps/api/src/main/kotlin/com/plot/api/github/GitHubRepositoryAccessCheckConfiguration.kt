package com.plot.api.github

import java.util.concurrent.ScheduledExecutorService
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
	@Qualifier("githubRepositoryAccessCheckTaskExecutor") taskExecutor: TaskExecutor,
	@Qualifier("githubWorkerRetryExecutor") retryExecutor: ScheduledExecutorService,
	worker: GitHubRepositoryAccessCheckWorker,
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
