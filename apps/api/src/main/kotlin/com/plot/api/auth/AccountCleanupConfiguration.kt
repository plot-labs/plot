package com.plot.api.auth

import com.plot.api.common.WorkerExecutors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class AccountCleanupConfiguration {
	@Bean
	fun accountCleanupTaskExecutor(): ThreadPoolTaskExecutor =
		WorkerExecutors.singleSlotTaskExecutor("plot-account-cleanup-")

	@Bean(destroyMethod = "shutdown")
	fun accountCleanupRetryExecutor(): ScheduledExecutorService =
		WorkerExecutors.daemonScheduler("plot-account-cleanup-retry")
}
