package com.plot.api.recovery

import com.plot.api.artifact.workflow.ArtifactWorkflowRunDispatcher
import com.plot.api.config.PlotAiProperties
import com.plot.api.github.GitHubProperties
import com.plot.api.github.GitHubReleaseDraftDispatcher
import com.plot.api.routine.AgentRunDispatcher
import com.plot.api.routine.RoutineAgentProperties
import com.plot.api.routine.RoutineRunDispatcher
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.SmartLifecycle
import org.springframework.core.task.TaskRejectedException
import org.springframework.stereotype.Component

data class RecoveryTickSummary(
	val queues: Map<String, QueueTickResult>,
)

data class QueueTickResult(
	val queueName: String,
	val enabled: Boolean,
	val runnableCount: Int,
	val oldestCreatedAt: Instant?,
	val dispatched: Boolean,
	val error: String? = null,
)

@Component
class RecoveryCoordinator @org.springframework.beans.factory.annotation.Autowired constructor(
	private val properties: RecoveryProperties,
	private val persistence: RecoveryCoordinatorPersistence,
	private val releaseDispatcher: GitHubReleaseDraftDispatcher,
	private val routineRunDispatcher: RoutineRunDispatcher,
	private val agentRunDispatcher: AgentRunDispatcher,
	private val artifactWorkflowDispatcher: ArtifactWorkflowRunDispatcher,
	private val gitHubProperties: GitHubProperties,
	private val routineAgentProperties: RoutineAgentProperties,
	private val plotAiProperties: PlotAiProperties,
	private val clock: Clock = Clock.systemUTC(),
	private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
) : SmartLifecycle, DisposableBean {

	private val log = LoggerFactory.getLogger(RecoveryCoordinator::class.java)
	private var scheduler: ScheduledExecutorService? = null
	private var running: Boolean = false

	constructor(
		properties: RecoveryProperties,
		persistence: RecoveryCoordinatorPersistence,
		releaseDispatcher: GitHubReleaseDraftDispatcher,
		routineRunDispatcher: RoutineRunDispatcher,
		agentRunDispatcher: AgentRunDispatcher,
		artifactWorkflowDispatcher: ArtifactWorkflowRunDispatcher,
		gitHubProperties: GitHubProperties,
		routineAgentProperties: RoutineAgentProperties,
		plotAiProperties: PlotAiProperties,
		clock: Clock = Clock.systemUTC(),
		meterRegistry: MeterRegistry = SimpleMeterRegistry(),
		scheduler: ScheduledExecutorService?,
	) : this(
		properties, persistence, releaseDispatcher, routineRunDispatcher,
		agentRunDispatcher, artifactWorkflowDispatcher, gitHubProperties,
		routineAgentProperties, plotAiProperties, clock, meterRegistry,
	) {
		this.scheduler = scheduler
	}

	override fun start() {
		if (!properties.enabled) return
		running = true
		val initialDelayMillis = properties.interval.toMillis().coerceAtLeast(100)
		val intervalMillis = properties.interval.toMillis().coerceAtLeast(100)
		val exec = scheduler ?: Executors.newSingleThreadScheduledExecutor { r ->
			Thread(r, "plot-recovery-coordinator").apply { isDaemon = true }
		}.also { scheduler = it }
		exec.scheduleWithFixedDelay(
			{
				try {
					tick()
				} catch (ex: Throwable) {
					log.error("Unhandled error in recovery coordinator tick", ex)
				}
			},
			initialDelayMillis,
			intervalMillis,
			TimeUnit.MILLISECONDS,
		)
		log.info(
			"Recovery coordinator started (interval={}, batchSize={}, observeOnly={})",
			properties.interval, properties.batchSize, properties.observeOnly,
		)
	}

	override fun stop() {
		running = false
		scheduler?.shutdownNow()
	}

	override fun isRunning(): Boolean = running

	override fun destroy() {
		stop()
	}

	fun tick(): RecoveryTickSummary {
		if (!properties.enabled) {
			return RecoveryTickSummary(emptyMap())
		}
		val now = clock.instant()
		val results = mutableMapOf<String, QueueTickResult>()

		// 1. Release drafts queue
		results["release"] = checkQueue(
			queueName = "release",
			workerEnabled = gitHubProperties.releaseAutomationEnabled,
			staleBefore = now.minus(gitHubProperties.releaseWorkerLeaseTimeout),
			now = now,
			fetchStats = { staleBefore -> persistence.findReleaseDraftStats(now, staleBefore, properties.batchSize) },
			dispatch = { releaseDispatcher.dispatch() },
		)

		// 2. Routine execution queue
		results["routine"] = checkQueue(
			queueName = "routine",
			workerEnabled = routineAgentProperties.workersEnabled,
			staleBefore = now.minus(routineAgentProperties.claimTimeout),
			now = now,
			fetchStats = { staleBefore -> persistence.findRoutineExecutionStats(now, staleBefore, properties.batchSize) },
			dispatch = { routineRunDispatcher.dispatch() },
		)

		// 3. Agent runs queue
		results["agent"] = checkQueue(
			queueName = "agent",
			workerEnabled = routineAgentProperties.workersEnabled,
			staleBefore = now.minus(routineAgentProperties.claimTimeout),
			now = now,
			fetchStats = { staleBefore -> persistence.findAgentRunStats(now, staleBefore, properties.batchSize) },
			dispatch = { agentRunDispatcher.dispatch() },
		)

		// 4. Artifact workflow queue
		results["artifact"] = checkQueue(
			queueName = "artifact",
			workerEnabled = plotAiProperties.workerEnabled,
			staleBefore = now.minus(plotAiProperties.claimTimeout),
			now = now,
			fetchStats = { staleBefore -> persistence.findArtifactWorkflowStats(now, staleBefore, properties.batchSize) },
			dispatch = { artifactWorkflowDispatcher.dispatch() },
		)

		return RecoveryTickSummary(results)
	}

	private fun checkQueue(
		queueName: String,
		workerEnabled: Boolean,
		staleBefore: Instant,
		now: Instant,
		fetchStats: (Instant) -> QueueRunnableStats,
		dispatch: () -> Unit,
	): QueueTickResult {
		if (!workerEnabled) {
			return QueueTickResult(
				queueName = queueName,
				enabled = false,
				runnableCount = 0,
				oldestCreatedAt = null,
				dispatched = false,
			)
		}

		val stats = try {
			fetchStats(staleBefore)
		} catch (ex: Exception) {
			log.error("Failed to query recovery stats for queue $queueName", ex)
			meterRegistry.counter("plot.recovery.tick.failures", "queue", queueName).increment()
			return QueueTickResult(
				queueName = queueName,
				enabled = true,
				runnableCount = 0,
				oldestCreatedAt = null,
				dispatched = false,
				error = ex.message,
			)
		}

		meterRegistry.gauge("plot.recovery.runnable.count", listOf(Tag.of("queue", queueName)), stats.runnableCount)
		stats.oldestCreatedAt?.let { oldest ->
			val ageSeconds = Duration.between(oldest, now).seconds.coerceAtLeast(0)
			meterRegistry.gauge("plot.recovery.oldest.age.seconds", listOf(Tag.of("queue", queueName)), ageSeconds)
		}

		var dispatched = false
		if (stats.runnableCount > 0 && !properties.observeOnly) {
			try {
				dispatch()
				dispatched = true
				meterRegistry.counter("plot.recovery.dispatched", "queue", queueName).increment()
			} catch (rejection: TaskRejectedException) {
				log.warn("Recovery dispatch rejected for queue $queueName (executor saturated): {}", rejection.message)
				meterRegistry.counter("plot.recovery.dispatch.rejected", "queue", queueName).increment()
			}
		}

		return QueueTickResult(
			queueName = queueName,
			enabled = true,
			runnableCount = stats.runnableCount,
			oldestCreatedAt = stats.oldestCreatedAt,
			dispatched = dispatched,
		)
	}
}
