package com.plot.api.github

import com.plot.api.common.WorkerExecutors
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
	fun githubRepositoryAccessCheckTaskExecutor(): ThreadPoolTaskExecutor =
		WorkerExecutors.singleSlotTaskExecutor("plot-github-access-check-")
}

@Component
class GitHubRepositoryAccessCheckDispatcher(
	@Qualifier("githubRepositoryAccessCheckTaskExecutor") taskExecutor: TaskExecutor,
	@Qualifier("githubWorkerRetryExecutor") retryExecutor: ScheduledExecutorService,
	worker: GitHubRepositoryAccessCheckWorker,
	properties: GitHubProperties,
) {
	private val delegate = GitHubWorkerDispatch(
		taskExecutor = taskExecutor,
		retryExecutor = retryExecutor,
		recover = worker::recover,
		drain = worker::drain,
		earliestRetryAt = worker::nextRetryAt,
		failureRecoveryDelay = properties.accessCheckLeaseTimeout,
	)

	fun dispatch() = delegate.dispatch()
}
