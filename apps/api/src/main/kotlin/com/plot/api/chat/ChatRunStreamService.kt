package com.plot.api.chat

import com.plot.api.agent.AgentRunStatus
import com.plot.api.common.ApiException
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.persistence.SqlExecutor
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Service
class ChatRunStreamService(
	private val progress: ChatRunProgressPersistence,
	private val sql: SqlExecutor,
) {
	private val subscriptions = ConcurrentHashMap<UUID, Subscription>()
	private val scan = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "chat-stream-scan").apply { isDaemon = true } }
	// ponytail: eight bounded socket writers; use async transport if slow subscribers saturate this pool.
	private val writers = ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, ArrayBlockingQueue(256),
		ThreadFactory { task -> Thread(task, "chat-stream-write").apply { isDaemon = true } })

	init { scan.scheduleWithFixedDelay(::refresh, 250, 250, TimeUnit.MILLISECONDS) }
	internal val activeSubscriptions: Int get() = subscriptions.size

	fun subscribe(principal: WorkspacePrincipal, runId: UUID, expiresAt: Instant? = null): SseEmitter {
		val key = principal.workspaceId to runId
		if (progress.load(key.first, key.second) == null) throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Agent run not found")
		if (principal !in authorized(setOf(principal)) || key !in readableSources(listOf(key)) ||
			expiresAt?.isAfter(Instant.now()) == false) denied()
		val subscription = Subscription(principal, runId, expiresAt)
		synchronized(subscriptions) {
			if (subscriptions.size >= 256 || subscriptions.values.count { it.principal == principal } >= 4) {
				throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "STREAM_LIMIT", "Too many active streams")
			}
			subscriptions[subscription.id] = subscription
		}
		try {
			val snapshot = progress.load(key.first, key.second)
				?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Agent run not found")
			subscription.lastSnapshot = snapshot
			subscription.offerSnapshot(snapshot)
		} catch (failure: RuntimeException) { subscription.remove(); subscription.emitter.complete(); throw failure }
		return subscription.emitter
	}

	private fun refresh() {
		val active = subscriptions.values.filter { it.active.get() && !it.closing.get() }
		if (active.isEmpty()) return
		try {
			val authorized = authorized(active.map { it.principal }.toSet())
			val keys = active.map { it.principal.workspaceId to it.runId }.distinct()
			val snapshots = progress.loadMany(keys)
			val readable = readableSources(keys)
			val now = System.nanoTime()
			for (subscription in active) {
				val key = subscription.principal.workspaceId to subscription.runId
				if (subscription.principal !in authorized || key !in readable || subscription.expiresAt?.isAfter(Instant.now()) == false) {
					subscription.finish(SseEmitter.event().name("error").data(mapOf("code" to "ACCESS_DENIED")))
					continue
				}
				val snapshot = snapshots[key]
				if (snapshot == null) {
					subscription.finish(SseEmitter.event().name("error").data(mapOf("code" to "NOT_FOUND")))
				} else if (now - subscription.startedAt >= 45_000_000_000) {
					subscription.finish(null)
				} else if (snapshot != subscription.lastSnapshot) {
					subscription.lastSnapshot = snapshot
					subscription.offerSnapshot(snapshot)
				} else if (now - subscription.lastHeartbeat >= 15_000_000_000) {
					subscription.lastHeartbeat = now
					subscription.offer(SseEmitter.event().comment("heartbeat"), false, replace = false)
				}
			}
		} catch (_: RuntimeException) {
			// Do not keep sending cached private snapshots when an authorization/storage read fails.
			active.forEach { it.finish(SseEmitter.event().name("error").data(mapOf("code" to "STREAM_UNAVAILABLE"))) }
		}
	}

	private fun authorized(principals: Set<WorkspacePrincipal>): Set<WorkspacePrincipal> = sql.query("""
		select requested.workspace_id, requested.user_id
		from (values ${principals.joinToString { "(?::uuid,?::uuid)" }}) requested(workspace_id,user_id)
		join workspaces w on w.id=requested.workspace_id and w.status='ACTIVE'
		join users u on u.id=requested.user_id and u.status='ACTIVE'
		join workspace_members m on m.workspace_id=w.id and m.user_id=u.id and m.status='ACTIVE'
	""".trimIndent(), { row, _ -> WorkspacePrincipal(row.getObject("workspace_id", UUID::class.java)!!, row.getObject("user_id", UUID::class.java)!!) },
		*principals.flatMap { listOf(it.workspaceId, it.userId) }.toTypedArray()).toSet()

	private fun readableSources(keys: List<Pair<UUID, UUID>>): Set<Pair<UUID, UUID>> = sql.query("""
		select a.workspace_id,a.id from agent_runs a
		where (a.workspace_id,a.id) in (${keys.joinToString { "(?,?)" }}) and not exists (
		  select 1 from agent_run_sources source
		  left join source_scopes scope on scope.workspace_id=source.workspace_id and scope.id=source.source_scope_id
		  left join source_namespaces namespace on namespace.workspace_id=scope.workspace_id and namespace.id=scope.source_namespace_id and namespace.provider=scope.provider
		  where source.workspace_id=a.workspace_id and source.agent_run_id=a.id and (
		    scope.id is null or scope.status<>'ACTIVE' or namespace.id is null or namespace.status<>'ACTIVE' or (not exists (
		      select 1 from chat_response_versions version where version.workspace_id=a.workspace_id
		        and version.agent_run_id=a.id and version.lineage_parent_version_id is not null
		    ) and not exists (
		      select 1 from connection_namespace_bindings binding
		      join connections connection on connection.workspace_id=binding.workspace_id and connection.id=binding.connection_id
		        and connection.provider=binding.provider and connection.status='ACTIVE'
		      where binding.workspace_id=namespace.workspace_id and binding.source_namespace_id=namespace.id
		        and binding.provider=namespace.provider and binding.status='ACTIVE'
		    ))
		  )
		)
	""".trimIndent(), { row, _ -> row.getObject("workspace_id", UUID::class.java)!! to row.getObject("id", UUID::class.java)!! },
		*keys.flatMap { listOf(it.first, it.second) }.toTypedArray()).toSet()

	private fun denied(): Nothing = throw ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied")

	private inner class Subscription(val principal: WorkspacePrincipal, val runId: UUID, val expiresAt: Instant?) {
		val id: UUID = UUID.randomUUID()
		// Allow the 45-second normal rotation to complete before the servlet timeout fires.
		val emitter = SseEmitter(50_000)
		val active = AtomicBoolean(true)
		val closing = AtomicBoolean(false)
		val startedAt = System.nanoTime()
		var lastHeartbeat = startedAt
		@Volatile var lastSnapshot: ChatRunSnapshot? = null
		private val pending = AtomicReference<Pair<SseEmitter.SseEventBuilder?, Boolean>?>(null)
		private val sending = AtomicBoolean(false)

		init {
			emitter.onCompletion(::remove)
			emitter.onTimeout(::remove)
			emitter.onError { remove() }
		}
		fun offerSnapshot(snapshot: ChatRunSnapshot) {
			val terminal = snapshot.status in setOf(AgentRunStatus.SUCCEEDED, AgentRunStatus.FAILED)
			if (terminal && !closing.compareAndSet(false, true)) return
			offer(SseEmitter.event().name("snapshot").data(snapshot), terminal)
		}
		fun finish(event: SseEmitter.SseEventBuilder?) {
			if (closing.compareAndSet(false, true)) offer(event, true)
		}
		@Synchronized
		fun offer(event: SseEmitter.SseEventBuilder?, complete: Boolean, replace: Boolean = true) {
			if (!active.get() || (closing.get() && !complete)) return
			if (replace) pending.set(event to complete)
			else if (!pending.compareAndSet(null, event to complete)) return
			drain()
		}
		private fun drain() {
			if (!sending.compareAndSet(false, true)) return
			try {
				writers.execute {
					try {
						while (active.get()) {
							val delivery = pending.getAndSet(null) ?: break
							delivery.first?.let(emitter::send)
							if (delivery.second) { remove(); emitter.complete(); break }
						}
					} catch (_: Exception) { remove(); emitter.complete() }
					finally { sending.set(false); if (pending.get() != null && active.get()) drain() }
				}
			} catch (_: RejectedExecutionException) { remove(); emitter.complete() }
		}
		fun remove() { active.set(false); pending.set(null); subscriptions.remove(id) }
	}

	@PreDestroy
	fun close() {
		subscriptions.values.forEach { it.finish(null) }
		scan.shutdownNow()
		writers.shutdown()
	}
}
