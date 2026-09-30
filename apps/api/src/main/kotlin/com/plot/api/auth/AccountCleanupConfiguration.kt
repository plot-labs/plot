package com.plot.api.auth

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class AccountCleanupConfiguration {
	@Bean
	fun accountCleanupTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix("plot-account-cleanup-")
		setStrictEarlyShutdown(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}

	@Bean(destroyMethod = "shutdown")
	fun accountCleanupRetryExecutor(): ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor { task ->
			Thread(task, "plot-account-cleanup-retry").apply { isDaemon = true }
		}
}
