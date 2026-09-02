package com.plot.api.github

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class GitHubWorkerConfiguration {
	@Bean(destroyMethod = "shutdown")
	fun githubWorkerRetryExecutor(): ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "plot-github-worker-retry").apply { isDaemon = true }
		}
}
