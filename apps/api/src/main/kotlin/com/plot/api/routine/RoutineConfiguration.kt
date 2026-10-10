package com.plot.api.routine

import com.plot.api.common.WorkerExecutors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RoutineConfiguration {
	@Bean
	fun routineTaskExecutor(): ThreadPoolTaskExecutor =
		WorkerExecutors.singleSlotTaskExecutor("plot-routine-")

	@Bean(destroyMethod = "shutdown")
	fun routineRetryExecutor(): ScheduledExecutorService =
		WorkerExecutors.daemonScheduler("plot-routine-retry")
}
