package com.plot.api.agent

import com.plot.api.common.WorkerExecutors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class AgentConfiguration {
	@Bean
	fun agentRunTaskExecutor(): ThreadPoolTaskExecutor =
		WorkerExecutors.singleSlotTaskExecutor("plot-agent-run-")

	@Bean(destroyMethod = "shutdown")
	fun agentRunRetryExecutor(): ScheduledExecutorService =
		WorkerExecutors.daemonScheduler("plot-agent-run-retry")
}
