package com.plot.api.artifact.workflow

import com.plot.api.ai.provider.ArtifactWorkflowModelGateway
import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.common.UuidGenerator
import com.plot.api.config.PlotAiProperties
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import com.plot.api.routine.RoutineAgentProperties
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
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
		sqlExecutor: JooqSqlExecutor,
		objectMapper: ObjectMapper,
		transactionExecutor: JooqTransactionExecutor,
		uuidGenerator: UuidGenerator,
		artifactRunPersistence: ArtifactRunPersistence,
	): ArtifactWorkflowPersistence = ArtifactWorkflowPersistence(
		sqlExecutor,
		objectMapper,
		transactionExecutor,
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
			Thread(task, "plot-artifact-workflow-heartbeat").apply { isDaemon = true }
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
	fun artifactWorkflowTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 1
		setThreadNamePrefix("plot-artifact-workflow-")
		setStrictEarlyShutdown(true)
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(10)
	}

	@Bean
	fun artifactWorkflowRunDispatcher(
		@Qualifier("artifactWorkflowTaskExecutor") artifactWorkflowTaskExecutor: TaskExecutor,
		worker: ArtifactWorkflowRunWorker,
		properties: PlotAiProperties,
	): ArtifactWorkflowRunDispatcher = ArtifactWorkflowRunDispatcher(
		artifactWorkflowTaskExecutor,
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
