package com.plot.api.recovery

import com.plot.api.agent.AgentRunWorker

import com.plot.api.artifact.workflow.ArtifactWorkflowRunDispatcher
import com.plot.api.config.PlotAiProperties
import com.plot.api.github.GitHubProperties
import com.plot.api.github.GitHubReleaseDraftDispatcher
import com.plot.api.agent.AgentRunDispatcher
import com.plot.api.agent.AgentProperties
import com.plot.api.routine.RoutineRunDispatcher
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.core.task.TaskRejectedException

class RecoveryCoordinatorTest {

	private val now = Instant.parse("2026-09-09T10:00:00Z")
	private val clock = Clock.fixed(now, ZoneOffset.UTC)
	private val meterRegistry = SimpleMeterRegistry()

	@Test
	fun `does not check queues or dispatch when recovery is disabled`() {
		val (coordinator, counts) = createCoordinator(
			properties = RecoveryProperties(enabled = false),
		)

		val summary = coordinator.tick()

		assertTrue(summary.queues.isEmpty())
		assertEquals(0, counts.releaseDispatches.get())
		assertEquals(0, counts.routineDispatches.get())
		assertEquals(0, counts.agentDispatches.get())
		assertEquals(0, counts.artifactDispatches.get())
	}

	@Test
	fun `discovers runnable rows and dispatches to enabled queues`() {
		val (coordinator, counts) = createCoordinator(
			properties = RecoveryProperties(enabled = true, batchSize = 10),
			releaseStats = QueueRunnableStats(runnableCount = 2, oldestCreatedAt = now.minusSeconds(60)),
			routineStats = QueueRunnableStats(runnableCount = 1, oldestCreatedAt = now.minusSeconds(30)),
			agentStats = QueueRunnableStats(runnableCount = 0, oldestCreatedAt = null),
			artifactStats = QueueRunnableStats(runnableCount = 3, oldestCreatedAt = now.minusSeconds(120)),
		)

		val summary = coordinator.tick()

		assertEquals(4, summary.queues.size)
		assertEquals(2, summary.queues["release"]?.runnableCount)
		assertTrue(summary.queues["release"]?.dispatched == true)
		assertEquals(1, counts.releaseDispatches.get())

		assertEquals(1, summary.queues["routine"]?.runnableCount)
		assertTrue(summary.queues["routine"]?.dispatched == true)
		assertEquals(1, counts.routineDispatches.get())

		assertEquals(0, summary.queues["agent"]?.runnableCount)
		assertFalse(summary.queues["agent"]?.dispatched == true)
		assertEquals(0, counts.agentDispatches.get())

		assertEquals(3, summary.queues["artifact"]?.runnableCount)
		assertTrue(summary.queues["artifact"]?.dispatched == true)
		assertEquals(1, counts.artifactDispatches.get())

		assertEquals(1.0, meterRegistry.find("plot.recovery.dispatched").tag("queue", "release").counter()?.count())
	}

	@Test
	fun `observeOnly records metrics without dispatching`() {
		val (coordinator, counts) = createCoordinator(
			properties = RecoveryProperties(enabled = true, observeOnly = true, batchSize = 10),
			releaseStats = QueueRunnableStats(runnableCount = 5, oldestCreatedAt = now.minusSeconds(300)),
		)

		val summary = coordinator.tick()

		assertEquals(5, summary.queues["release"]?.runnableCount)
		assertFalse(summary.queues["release"]?.dispatched == true)
		assertEquals(0, counts.releaseDispatches.get())
		assertNull(meterRegistry.find("plot.recovery.dispatched").tag("queue", "release").counter())
	}

	@Test
	fun `skips disabled worker queues`() {
		val (coordinator, counts) = createCoordinator(
			properties = RecoveryProperties(enabled = true),
			releaseAutomationEnabled = false,
			workersEnabled = false,
			artifactWorkerEnabled = false,
			releaseStats = QueueRunnableStats(runnableCount = 5, oldestCreatedAt = now.minusSeconds(100)),
		)

		val summary = coordinator.tick()

		assertFalse(summary.queues["release"]?.enabled == true)
		assertFalse(summary.queues["release"]?.dispatched == true)
		assertEquals(0, counts.releaseDispatches.get())

		assertFalse(summary.queues["routine"]?.enabled == true)
		assertFalse(summary.queues["agent"]?.enabled == true)
		assertFalse(summary.queues["artifact"]?.enabled == true)
	}

	@Test
	fun `single queue error does not block other queues`() {
		val counts = DispatchCounts()
		val persistence = object : FakeRecoveryPersistence() {
			override fun findReleaseDraftStats(now: Instant, staleBefore: Instant, batchSize: Int): QueueRunnableStats {
				error("DB connection timeout on release table")
			}
			override fun findRoutineExecutionStats(now: Instant, staleBefore: Instant, batchSize: Int): QueueRunnableStats {
				return QueueRunnableStats(runnableCount = 1, oldestCreatedAt = now.minusSeconds(10))
			}
		}

		val coordinator = RecoveryCoordinator(
			properties = RecoveryProperties(enabled = true),
			persistence = persistence,
			releaseDispatcher = GitHubReleaseDraftDispatcher { counts.releaseDispatches.incrementAndGet() },
			routineRunDispatcher = makeMockRoutineDispatcher { counts.routineDispatches.incrementAndGet() },
			agentRunDispatcher = makeMockAgentDispatcher { counts.agentDispatches.incrementAndGet() },
			artifactWorkflowDispatcher = makeMockArtifactDispatcher { counts.artifactDispatches.incrementAndGet() },
			gitHubProperties = GitHubProperties(releaseAutomationEnabled = true),
			routineAgentProperties = AgentProperties(workersEnabled = true),
			plotAiProperties = PlotAiProperties(workerEnabled = true),
			clock = clock,
			meterRegistry = meterRegistry,
		)

		val summary = coordinator.tick()

		// Release queue had error
		assertEquals("DB connection timeout on release table", summary.queues["release"]?.error)
		assertFalse(summary.queues["release"]?.dispatched == true)
		assertEquals(1.0, meterRegistry.find("plot.recovery.tick.failures").tag("queue", "release").counter()?.count())

		// Routine queue still ran and dispatched successfully!
		assertEquals(1, summary.queues["routine"]?.runnableCount)
		assertTrue(summary.queues["routine"]?.dispatched == true)
		assertEquals(1, counts.routineDispatches.get())
	}

	@Test
	fun `handles TaskRejectedException when executor is saturated`() {
		val persistence = object : FakeRecoveryPersistence() {
			override fun findReleaseDraftStats(now: Instant, staleBefore: Instant, batchSize: Int): QueueRunnableStats {
				return QueueRunnableStats(runnableCount = 1, oldestCreatedAt = now.minusSeconds(5))
			}
		}

		val coordinator = RecoveryCoordinator(
			properties = RecoveryProperties(enabled = true),
			persistence = persistence,
			releaseDispatcher = GitHubReleaseDraftDispatcher { throw TaskRejectedException("Executor queue is full") },
			routineRunDispatcher = makeMockRoutineDispatcher { },
			agentRunDispatcher = makeMockAgentDispatcher { },
			artifactWorkflowDispatcher = makeMockArtifactDispatcher { },
			gitHubProperties = GitHubProperties(releaseAutomationEnabled = true),
			routineAgentProperties = AgentProperties(workersEnabled = true),
			plotAiProperties = PlotAiProperties(workerEnabled = true),
			clock = clock,
			meterRegistry = meterRegistry,
		)

		val summary = coordinator.tick()

		assertEquals(1, summary.queues["release"]?.runnableCount)
		assertFalse(summary.queues["release"]?.dispatched == true)
		assertEquals(1.0, meterRegistry.find("plot.recovery.dispatch.rejected").tag("queue", "release").counter()?.count())
	}

	private class DispatchCounts {
		val releaseDispatches = AtomicInteger()
		val routineDispatches = AtomicInteger()
		val agentDispatches = AtomicInteger()
		val artifactDispatches = AtomicInteger()
	}

	private fun createCoordinator(
		properties: RecoveryProperties,
		releaseAutomationEnabled: Boolean = true,
		workersEnabled: Boolean = true,
		artifactWorkerEnabled: Boolean = true,
		releaseStats: QueueRunnableStats = QueueRunnableStats(0, null),
		routineStats: QueueRunnableStats = QueueRunnableStats(0, null),
		agentStats: QueueRunnableStats = QueueRunnableStats(0, null),
		artifactStats: QueueRunnableStats = QueueRunnableStats(0, null),
	): Pair<RecoveryCoordinator, DispatchCounts> {
		val counts = DispatchCounts()
		val persistence = object : FakeRecoveryPersistence() {
			override fun findReleaseDraftStats(now: Instant, staleBefore: Instant, batchSize: Int) = releaseStats
			override fun findRoutineExecutionStats(now: Instant, staleBefore: Instant, batchSize: Int) = routineStats
			override fun findAgentRunStats(now: Instant, staleBefore: Instant, batchSize: Int) = agentStats
			override fun findArtifactWorkflowStats(now: Instant, staleBefore: Instant, batchSize: Int) = artifactStats
		}

		val coordinator = RecoveryCoordinator(
			properties = properties,
			persistence = persistence,
			releaseDispatcher = GitHubReleaseDraftDispatcher { counts.releaseDispatches.incrementAndGet() },
			routineRunDispatcher = makeMockRoutineDispatcher { counts.routineDispatches.incrementAndGet() },
			agentRunDispatcher = makeMockAgentDispatcher { counts.agentDispatches.incrementAndGet() },
			artifactWorkflowDispatcher = makeMockArtifactDispatcher { counts.artifactDispatches.incrementAndGet() },
			gitHubProperties = GitHubProperties(releaseAutomationEnabled = releaseAutomationEnabled),
			routineAgentProperties = AgentProperties(workersEnabled = workersEnabled),
			plotAiProperties = PlotAiProperties(workerEnabled = artifactWorkerEnabled),
			clock = clock,
			meterRegistry = meterRegistry,
		)
		return coordinator to counts
	}

	private open class FakeRecoveryPersistence : RecoveryCoordinatorPersistence(
		sqlExecutor = org.mockito.Mockito.mock(com.plot.api.persistence.SqlExecutor::class.java),
	)

	private fun makeMockRoutineDispatcher(onDispatch: () -> Unit): RoutineRunDispatcher {
		return object : RoutineRunDispatcher(
			taskExecutor = org.springframework.core.task.SyncTaskExecutor(),
			worker = org.mockito.Mockito.mock(com.plot.api.routine.RoutineWorker::class.java),
			agentProperties = AgentProperties(),
			retryExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(),
		) {
			override fun dispatch() {
				onDispatch()
			}
		}
	}

	private fun makeMockAgentDispatcher(onDispatch: () -> Unit): AgentRunDispatcher {
		return object : AgentRunDispatcher(
			taskExecutor = org.springframework.core.task.SyncTaskExecutor(),
			worker = org.mockito.Mockito.mock(com.plot.api.agent.AgentRunWorker::class.java),
			properties = AgentProperties(),
			retryExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(),
		) {
			override fun dispatch() {
				onDispatch()
			}
		}
	}

	private fun makeMockArtifactDispatcher(onDispatch: () -> Unit): ArtifactWorkflowRunDispatcher {
		return ArtifactWorkflowRunDispatcher(
			taskExecutor = org.springframework.core.task.SyncTaskExecutor(),
			drainBatch = {
				onDispatch()
				false
			},
		)
	}
}
