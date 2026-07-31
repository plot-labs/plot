package com.plot.api.github

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component

@Component
class GitHubRepositoryMonitoringDispatcher(
	@Qualifier("githubRepositoryMonitoringTaskExecutor") private val taskExecutor: TaskExecutor,
	private val worker: GitHubRepositoryMonitoringWorker,
) {
	fun dispatch() {
		try {
			taskExecutor.execute { worker.drain() }
		} catch (_: TaskRejectedException) {
			// The durable poller will rediscover queued monitoring work.
		}
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

@Component
class GitHubRepositoryMonitoringPoller(
	private val worker: GitHubRepositoryMonitoringWorker,
	private val dispatcher: GitHubRepositoryMonitoringDispatcher,
	private val properties: GitHubProperties,
) {
	@Scheduled(fixedDelayString = "\${plot.github.monitoring-analysis-poll-delay:PT5S}")
	fun poll() {
		if (properties.enabled) {
			worker.recover()
			dispatcher.dispatch()
		}
	}
}
