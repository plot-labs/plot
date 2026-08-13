package com.plot.api.generation

import com.plot.api.ai.provider.GenerationModelGateway
import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.common.UuidGenerator
import com.plot.api.config.PlotAiProperties
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.routine.RoutineAgentProperties
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

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
		artifactRunPersistence: ArtifactRunPersistence,
	): GenerationPersistence = GenerationPersistence(
		jdbcTemplate,
		objectMapper,
		transactionTemplate,
		uuidGenerator,
		artifactRunPersistence,
	)

	@Bean
	fun generationRunWorker(
		persistence: GenerationPersistence,
		workflowService: GenerationWorkflowService,
		modelGateway: GenerationModelGateway,
		properties: PlotAiProperties,
		leaseFactory: GenerationRunLeaseFactory,
		observationRegistry: ObservationRegistry,
		workspaceAccessService: WorkspaceAccessService,
		routineAgentProperties: RoutineAgentProperties,
	): GenerationRunWorker = GenerationRunWorker(
		persistence,
		workflowService,
		modelGateway,
		claimTimeout = properties.claimTimeout,
		retryInitialDelay = properties.retryInitialDelay,
		leaseFactory = leaseFactory,
		observationRegistry = observationRegistry,
		workspaceAccessService = workspaceAccessService,
		agentRunsEnabled = routineAgentProperties.workersEnabled,
	)

	@Bean(destroyMethod = "shutdown")
	fun generationHeartbeatExecutor(): ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor { task ->
			Thread(task, "plot-generation-heartbeat").apply { isDaemon = true }
		}

	@Bean
	fun generationRunLeaseFactory(
		@Qualifier("generationHeartbeatExecutor") heartbeatExecutor: ScheduledExecutorService,
		persistence: GenerationPersistence,
		properties: PlotAiProperties,
	): GenerationRunLeaseFactory = ScheduledGenerationRunLeaseFactory(
		executor = heartbeatExecutor,
		heartbeatInterval = heartbeatInterval(properties.claimTimeout),
		clock = Clock.systemUTC(),
		renewClaim = persistence::renewClaim,
	)

	@Bean
	fun generationTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix("plot-generation-")
		setStrictEarlyShutdown(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}

	@Bean
	fun generationRunDispatcher(
		@Qualifier("generationTaskExecutor") generationTaskExecutor: TaskExecutor,
		worker: GenerationRunWorker,
		properties: PlotAiProperties,
	): GenerationRunDispatcher = GenerationRunDispatcher(
		generationTaskExecutor,
		properties.workerEnabled,
	) { worker.drain() > 0 }

	@Bean
	fun generationRunRecovery(
		persistence: GenerationPersistence,
		dispatcher: GenerationRunDispatcher,
		properties: PlotAiProperties,
	): GenerationRunRecovery = GenerationRunRecovery(
		persistence,
		dispatcher,
		claimTimeout = properties.claimTimeout,
		workerEnabled = properties.workerEnabled,
	)

	private fun heartbeatInterval(claimTimeout: Duration): Duration =
		Duration.ofMillis(maxOf(MINIMUM_HEARTBEAT_MILLIS, claimTimeout.toMillis() / 3))

	private companion object {
		const val MINIMUM_HEARTBEAT_MILLIS = 10L
	}
}
