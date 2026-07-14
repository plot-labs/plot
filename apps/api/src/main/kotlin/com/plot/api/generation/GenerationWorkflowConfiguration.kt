package com.plot.api.generation

import com.plot.api.ai.provider.GenerationModelGateway
import com.plot.api.common.UuidGenerator
import com.plot.api.config.PlotAiProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
class GenerationWorkflowConfiguration {
	@Bean
	fun evidenceSnapshotService(uuidGenerator: UuidGenerator): EvidenceSnapshotService =
		EvidenceSnapshotService(uuidGenerator::next)

	@Bean
	fun modelOutputValidator(): ModelOutputValidator = ModelOutputValidator()

	@Bean
	fun generationWorkflowService(
		validator: ModelOutputValidator,
		uuidGenerator: UuidGenerator,
	): GenerationWorkflowService = GenerationWorkflowService(validator, uuidGenerator::next)

	@Bean
	fun generationPersistence(
		jdbcTemplate: JdbcTemplate,
		objectMapper: ObjectMapper,
		transactionTemplate: TransactionTemplate,
		uuidGenerator: UuidGenerator,
	): GenerationPersistence = GenerationPersistence(jdbcTemplate, objectMapper, transactionTemplate, uuidGenerator)

	@Bean
	fun generationRunWorker(
		persistence: GenerationPersistence,
		workflowService: GenerationWorkflowService,
		modelGateway: GenerationModelGateway,
		properties: PlotAiProperties,
	): GenerationRunWorker = GenerationRunWorker(persistence, workflowService, modelGateway, claimTimeout = properties.claimTimeout)

	@Bean
	fun generationTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix("plot-generation-")
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}

	@Bean
	fun generationRunDispatcher(
		@Qualifier("generationTaskExecutor") generationTaskExecutor: TaskExecutor,
		worker: GenerationRunWorker,
	): GenerationRunDispatcher = GenerationRunDispatcher(generationTaskExecutor) { worker.drain() > 0 }

	@Bean
	fun generationRunRecovery(
		persistence: GenerationPersistence,
		dispatcher: GenerationRunDispatcher,
		properties: PlotAiProperties,
	): GenerationRunRecovery = GenerationRunRecovery(persistence, dispatcher, claimTimeout = properties.claimTimeout)
}
