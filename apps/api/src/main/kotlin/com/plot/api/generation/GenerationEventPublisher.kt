package com.plot.api.generation

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

data class GenerationProgressEvent(
	val runId: UUID,
	val runStatus: String,
	val sequence: Long,
)

class GenerationEventPublisher : AutoCloseable {
	private val subscriptions = ConcurrentHashMap<SubscriptionKey, Subscription>()
	private val sequence = AtomicLong()
	private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
		Thread(runnable, "plot-generation-events").apply { isDaemon = true }
	}.also { executor ->
		executor.scheduleAtFixedRate(::sendHeartbeats, 15, 15, TimeUnit.SECONDS)
	}

	fun subscribe(workspaceId: UUID, runId: UUID): SseEmitter {
		val emitter = SseEmitter(0L)
		val key = SubscriptionKey(workspaceId, runId)
		lateinit var subscription: Subscription
		var terminal: GenerationRunStatus? = null
		while (true) {
			val candidate = subscriptions.computeIfAbsent(key) { Subscription() }
			var retry = false
			synchronized(candidate.lock) {
				if (subscriptions[key] !== candidate) {
					retry = true
				} else {
					val known = candidate.terminal
					if (known != null && known.expiresAt.isAfter(Instant.now())) {
						terminal = known.status
					} else {
						if (known != null) candidate.terminal = null
						candidate.emitters += emitter
					}
				}
			}
			if (!retry) {
				subscription = candidate
				break
			}
		}
		val remove = { remove(subscription, emitter) }
		emitter.onCompletion(remove)
		emitter.onTimeout(remove)
		emitter.onError { remove() }
		try {
			if (terminal != null) {
				sendEvent(emitter, runId, terminal)
				emitter.complete()
			} else {
				emitter.send(SseEmitter.event().comment("connected"))
			}
		} catch (_: Exception) {
			remove()
		}
		return emitter
	}

	fun publish(checkpoint: DurableGenerationCheckpoint) {
		publish(checkpoint.workspaceId, checkpoint.runId, checkpoint.runStatus)
	}

	fun publish(workspaceId: UUID, runId: UUID, status: GenerationRunStatus) {
		val key = SubscriptionKey(workspaceId, runId)
		val subscription = subscriptions.computeIfAbsent(key) { Subscription() }
		val terminal = status in GenerationRunStatus.terminalOrPaused
		synchronized(subscription.lock) {
			if (terminal) subscription.terminal = TerminalStatus(status, Instant.now().plusSeconds(60))
			subscription.emitters.toList().forEach { emitter ->
				try {
					sendEvent(emitter, runId, status)
					if (terminal) emitter.complete()
				} catch (_: Exception) {
					subscription.emitters.remove(emitter)
				}
			}
			if (terminal) subscription.emitters.clear()
		}
	}

	private fun sendEvent(emitter: SseEmitter, runId: UUID, status: GenerationRunStatus) {
		val eventSequence = sequence.incrementAndGet()
		emitter.send(
			SseEmitter.event()
				.id(eventSequence.toString())
				.name("checkpoint")
				.data(GenerationProgressEvent(runId, status.name, eventSequence)),
		)
	}

	private fun sendHeartbeats() {
		val now = Instant.now()
			subscriptions.forEach { (key, subscription) ->
				val expired = synchronized(subscription.lock) {
					if (subscription.terminal?.expiresAt?.isBefore(now) == true && subscription.emitters.isEmpty()) {
						subscription.terminal = null
						subscriptions.remove(key, subscription)
						true
					} else false
				}
			if (!expired) synchronized(subscription.lock) {
				subscription.emitters.toList().forEach { emitter ->
					try {
						emitter.send(SseEmitter.event().comment("heartbeat"))
					} catch (_: Exception) {
						subscription.emitters.remove(emitter)
					}
				}
			}
		}
	}

	private fun remove(subscription: Subscription, emitter: SseEmitter) {
		synchronized(subscription.lock) {
			subscription.emitters.remove(emitter)
		}
	}

	override fun close() {
		heartbeatExecutor.shutdownNow()
		subscriptions.values.flatMap { it.emitters.toList() }.forEach { it.complete() }
		subscriptions.clear()
	}

	private class Subscription {
		val lock = Any()
		val emitters = CopyOnWriteArraySet<SseEmitter>()
		var terminal: TerminalStatus? = null
	}

	private data class TerminalStatus(val status: GenerationRunStatus, val expiresAt: Instant)
	private data class SubscriptionKey(val workspaceId: UUID, val runId: UUID)
}

class GenerationCheckpointEventObserver(
	private val publisher: GenerationEventPublisher,
) : GenerationCheckpointObserver {
	override fun afterDurableCheckpoint(checkpoint: DurableGenerationCheckpoint) {
		publisher.publish(checkpoint)
	}

	override fun afterRunStatus(workspaceId: UUID, runId: UUID, status: GenerationRunStatus) {
		publisher.publish(workspaceId, runId, status)
	}
}
