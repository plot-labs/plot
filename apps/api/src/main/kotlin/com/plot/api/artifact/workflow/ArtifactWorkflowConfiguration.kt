package com.plot.api.artifact.workflow

import com.plot.api.ai.provider.ArtifactWorkflowModelGateway
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
class ArtifactWorkflowConfiguration {
	@Bean
	fun evidenceSnapshotService(uuidGenerator: UuidGenerator): EvidenceSnapshotService =
		EvidenceSnapshotService(uuidGenerator::next)

	@Bean
	fun modelOutputValidator(): ModelOutputValidator = ModelOutputValidator()

	@Bean
	fun artifactWorkflowService(
		validator: ModelOutputValidator,
		uuidGenerator: UuidGenerator,
	): ArtifactWorkflowService = ArtifactWorkflowService(validator, uuidGenerator::next)

	@Bean
	fun artifactWorkflowPersistence(
		jdbcTemplate: JdbcTemplate,
		objectMapper: ObjectMapper,
		transactionTemplate: TransactionTemplate,
		uuidGenerator: UuidGenerator,
		artifactRunPersistence: ArtifactRunPersistence,
	): ArtifactWorkflowPersistence = ArtifactWorkflowPersistence(
		jdbcTemplate,
		objectMapper,
		transactionTemplate,
		uuidGenerator,
		artifactRunPersistence,
	)

	@Bean
	fun artifactWorkflowRunWorker(
		persistence: ArtifactWorkflowPersistence,
		workflowService: ArtifactWorkflowService,
		modelGateway: ArtifactWorkflowModelGateway,
		properties: PlotAiProperties,
		leaseFactory: ArtifactWorkflowRunLeaseFactory,
		observationRegistry: ObservationRegistry,
		workspaceAccessService: WorkspaceAccessService,
		routineAgentProperties: RoutineAgentProperties,
	): ArtifactWorkflowRunWorker = ArtifactWorkflowRunWorker(
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
	fun artifactWorkflowHeartbeatExecutor(): ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor { task ->
			Thread(task, "plot-generation-heartbeat").apply { isDaemon = true }
		}

	@Bean
	fun artifactWorkflowRunLeaseFactory(
		@Qualifier("artifactWorkflowHeartbeatExecutor") heartbeatExecutor: ScheduledExecutorService,
		persistence: ArtifactWorkflowPersistence,
		properties: PlotAiProperties,
	): ArtifactWorkflowRunLeaseFactory = ScheduledArtifactWorkflowRunLeaseFactory(
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
	fun artifactWorkflowRunDispatcher(
		@Qualifier("generationTaskExecutor") generationTaskExecutor: TaskExecutor,
		worker: ArtifactWorkflowRunWorker,
		properties: PlotAiProperties,
	): ArtifactWorkflowRunDispatcher = ArtifactWorkflowRunDispatcher(
		generationTaskExecutor,
		properties.workerEnabled,
	) { worker.drain() > 0 }

	@Bean
	fun artifactWorkflowRunRecovery(
		persistence: ArtifactWorkflowPersistence,
		dispatcher: ArtifactWorkflowRunDispatcher,
		properties: PlotAiProperties,
	): ArtifactWorkflowRunRecovery = ArtifactWorkflowRunRecovery(
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
