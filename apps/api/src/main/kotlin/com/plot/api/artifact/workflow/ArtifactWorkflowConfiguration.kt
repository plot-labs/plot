package com.plot.api.artifact.workflow

import com.plot.api.ai.provider.ArtifactWorkflowModelGateway
import com.plot.api.artifact.run.ArtifactRunPersistence
import com.plot.api.common.UuidGenerator
import com.plot.api.config.PlotAiProperties
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import com.plot.api.routine.RoutineAgentProperties
import com.plot.api.routine.ArtifactWorkflowAgentRunCompletionHandler
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
	fun artifactWorkflowQueryPersistence(
		sqlExecutor: JooqSqlExecutor,
		objectMapper: ObjectMapper,
	): ArtifactWorkflowQueryPersistence = ArtifactWorkflowQueryPersistence(
		sqlExecutor = sqlExecutor,
		objectMapper = objectMapper,
	)

	@Bean
	fun artifactWorkflowMaterializationPersistence(
		sqlExecutor: JooqSqlExecutor,
		objectMapper: ObjectMapper,
		uuidGenerator: UuidGenerator,
	): ArtifactWorkflowMaterializationPersistence = ArtifactWorkflowMaterializationPersistence(
		sqlExecutor = sqlExecutor,
		objectMapper = objectMapper,
		uuidGenerator = uuidGenerator,
	)

	@Bean
	fun artifactWorkflowAdmissionPersistence(
		sqlExecutor: JooqSqlExecutor,
		objectMapper: ObjectMapper,
		transactionExecutor: JooqTransactionExecutor,
		uuidGenerator: UuidGenerator,
		artifactRunPersistence: ArtifactRunPersistence,
		queryPersistence: ArtifactWorkflowQueryPersistence,
		materializationPersistence: ArtifactWorkflowMaterializationPersistence,
	): ArtifactWorkflowAdmissionPersistence = ArtifactWorkflowAdmissionPersistence(
		sqlExecutor = sqlExecutor,
		objectMapper = objectMapper,
		transactionExecutor = transactionExecutor,
		uuidGenerator = uuidGenerator,
		artifactRunPersistence = artifactRunPersistence,
		queryPersistence = queryPersistence,
		materializationPersistence = materializationPersistence,
	)

	@Bean
	fun artifactWorkflowExecutionPersistence(
		sqlExecutor: JooqSqlExecutor,
		objectMapper: ObjectMapper,
		transactionExecutor: JooqTransactionExecutor,
		uuidGenerator: UuidGenerator,
		artifactRunPersistence: ArtifactRunPersistence,
		materializationPersistence: ArtifactWorkflowMaterializationPersistence,
	): ArtifactWorkflowExecutionPersistence = ArtifactWorkflowExecutionPersistence(
		sqlExecutor = sqlExecutor,
		objectMapper = objectMapper,
		transactionExecutor = transactionExecutor,
		uuidGenerator = uuidGenerator,
		artifactRunPersistence = artifactRunPersistence,
		materializationPersistence = materializationPersistence,
	)

	@Bean
	fun artifactWorkflowRecoveryPersistence(
		sqlExecutor: JooqSqlExecutor,
		transactionExecutor: JooqTransactionExecutor,
	): ArtifactWorkflowRecoveryPersistence = ArtifactWorkflowRecoveryPersistence(
		sqlExecutor = sqlExecutor,
		transactionExecutor = transactionExecutor,
	)

	@Bean
	fun artifactWorkflowRunWorker(
		executionPersistence: ArtifactWorkflowExecutionPersistence,
		queryPersistence: ArtifactWorkflowQueryPersistence,
		workflowService: ArtifactWorkflowService,
		modelGateway: ArtifactWorkflowModelGateway,
		properties: PlotAiProperties,
		leaseFactory: ArtifactWorkflowRunLeaseFactory,
		observationRegistry: ObservationRegistry,
		workspaceAccessService: WorkspaceAccessService,
		routineAgentProperties: RoutineAgentProperties,
		agentRunCompletion: ArtifactWorkflowAgentRunCompletionHandler,
	): ArtifactWorkflowRunWorker = ArtifactWorkflowRunWorker(
		executionPersistence = executionPersistence,
		queryPersistence = queryPersistence,
		workflowService = workflowService,
		modelGateway = modelGateway,
		claimTimeout = properties.claimTimeout,
		retryInitialDelay = properties.retryInitialDelay,
		leaseFactory = leaseFactory,
		observationRegistry = observationRegistry,
		workspaceAccessService = workspaceAccessService,
		agentRunsEnabled = routineAgentProperties.workersEnabled,
		agentRunCompletion = agentRunCompletion,
	)

	@Bean(destroyMethod = "shutdown")
	fun artifactWorkflowHeartbeatExecutor(): ScheduledExecutorService =
		Executors.newSingleThreadScheduledExecutor { task ->
			Thread(task, "plot-artifact-workflow-heartbeat").apply { isDaemon = true }
		}

	@Bean
	fun artifactWorkflowRunLeaseFactory(
		@Qualifier("artifactWorkflowHeartbeatExecutor") heartbeatExecutor: ScheduledExecutorService,
		executionPersistence: ArtifactWorkflowExecutionPersistence,
		properties: PlotAiProperties,
	): ArtifactWorkflowRunLeaseFactory = ScheduledArtifactWorkflowRunLeaseFactory(
		executor = heartbeatExecutor,
		heartbeatInterval = heartbeatInterval(properties.claimTimeout),
		clock = Clock.systemUTC(),
		renewClaim = executionPersistence::renewClaim,
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
		recoveryPersistence: ArtifactWorkflowRecoveryPersistence,
		dispatcher: ArtifactWorkflowRunDispatcher,
		properties: PlotAiProperties,
	): ArtifactWorkflowRunRecovery = ArtifactWorkflowRunRecovery(
		recoveryPersistence,
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
