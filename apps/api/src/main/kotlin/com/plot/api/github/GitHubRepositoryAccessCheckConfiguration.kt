package com.plot.api.github

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.annotation.Scheduled
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
		try {
			taskExecutor.execute { worker.drain() }
		} catch (_: TaskRejectedException) {
			// The durable poller will rediscover queued access checks.
		}
	}
}

@Component
class GitHubRepositoryAccessCheckPoller(
	private val worker: GitHubRepositoryAccessCheckWorker,
	private val dispatcher: GitHubRepositoryAccessCheckDispatcher,
	private val properties: GitHubProperties,
) {
	@Scheduled(fixedDelayString = "\${plot.github.access-check-poll-delay:PT5S}")
	fun poll() {
		if (properties.enabled) {
			worker.recover()
			dispatcher.dispatch()
		}
	}
}
