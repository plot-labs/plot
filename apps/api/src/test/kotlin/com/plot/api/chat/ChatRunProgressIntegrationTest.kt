package com.plot.api.chat

import com.plot.api.TestcontainersConfiguration
import com.plot.api.agent.*
import com.plot.api.chat.dto.CreateChatAgentRunRequest
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.routine.AgentRunWorkerIntegrationTest
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class, AgentRunWorkerIntegrationTest.Config::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true", "plot.routine-agent.workers-enabled=true", "plot.routine-agent.auto-dispatch-enabled=false", "plot.polar.credits-enabled=false", "server.address=127.0.0.1"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatRunProgressIntegrationTest {
	@org.springframework.boot.test.web.server.LocalServerPort private var port: Int = 0
	@Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
	@Autowired private lateinit var bootstrap: DevBootstrapService
	@Autowired private lateinit var dev: DevContext
	@Autowired private lateinit var jdbc: JdbcTemplate
	@Autowired private lateinit var queries: ChatQueryService
	@Autowired private lateinit var runs: ChatRunService
	@Autowired private lateinit var executions: AgentRunExecutionPersistence
	@Autowired private lateinit var worker: AgentRunWorker
	@Autowired private lateinit var model: com.plot.api.routine.ScriptedAgentRuntime
	@Autowired private lateinit var streams: ChatRunStreamService
	@Autowired private lateinit var progress: ChatRunProgressPersistence

	@BeforeEach
	fun isolate() {
		bootstrap.bootstrap()
		model.reset()
		jdbc.update("update agent_runs set status='FAILED', failure_code='TEST_ISOLATION', claimed_by=null, claimed_at=null, next_attempt_at=null, finished_at=now(), updated_at=now() where status in ('QUEUED','RUNNING')")
		jdbc.update("update workspaces set plan='founding', entitlement_status='active', access_mode='full' where id=?", dev.devWorkspaceId)
	}

	@Test
	fun `snapshot survives reconstruction and resets invocation epoch`() {
		val claim = admitAndClaim()
		val first = assertNotNull(progress.beginAgent(claim))
		progress.updateAgent(claim, first, "RESPONDING", "안녕")
		val saved = assertNotNull(progress.load(claim.workspaceId, claim.agentRunId))
		assertEquals("안녕", saved.responseText)
		assertEquals(first, saved.epoch)
		val second = assertNotNull(progress.beginAgent(claim))
		val reset = assertNotNull(progress.load(claim.workspaceId, claim.agentRunId))
		assertTrue(second > first)
		assertTrue(reset.revision > saved.revision)
		assertEquals("", reset.responseText)
		assertFailsWith<AgentRunClaimLostException> { progress.updateAgent(claim, first, "RESPONDING", "stale") }
	}

	@Test
	fun `reclaimed and terminal runs reject old callbacks and another workspace cannot read`() {
		val old = admitAndClaim()
		val epoch = assertNotNull(progress.beginAgent(old))
		val next = assertNotNull(executions.claimNextAgentRun("new-worker", Instant.now(), Instant.now().plusSeconds(10)))
		assertFailsWith<AgentRunClaimLostException> { progress.updateAgent(old, epoch, "RESPONDING", "stale") }
		val nextEpoch = assertNotNull(progress.beginAgent(next))
		progress.updateAgent(next, nextEpoch, "RESPONDING", "current")
		assertNull(progress.load(UUID.randomUUID(), old.agentRunId))
		jdbc.update("update agent_runs set status='FAILED', failure_code='TEST_FAILURE', finished_at=now(), updated_at=now() where id=?", old.agentRunId)
		val terminal = assertNotNull(progress.load(old.workspaceId, old.agentRunId))
		assertEquals("FAILED", terminal.phase)
		assertEquals(AgentRunStatus.FAILED, terminal.status)
		assertEquals("current", terminal.responseText)
		assertFailsWith<AgentRunClaimLostException> { progress.updateAgent(next, nextEpoch, "RESPONDING", "late") }
		jdbc.update("delete from agent_runs where id=?", old.agentRunId)
		assertEquals(0, jdbc.queryForObject("select count(*) from chat_run_progress where agent_run_id=?", Int::class.java, old.agentRunId))
	}

	@Test
	fun `worker persists real intermediate deltas before final projection`() {
		val run = runs.admit(CreateChatAgentRunRequest("Say hello"), "worker-stream-${UUID.randomUUID()}")
		model.nativeRuntime = object : com.plot.api.ai.provider.AgentRuntime {
			override fun run(host: com.plot.api.ai.provider.AgentRuntimeHost): com.plot.api.ai.provider.AgentRuntimeResult {
				host.beforeModel()
				host.onText("first ")
				assertEquals("first ", progress.load(dev.devWorkspaceId, run.id)?.responseText)
				host.onText("second")
				host.afterModel(model.usage)
				assertEquals("first second", progress.load(dev.devWorkspaceId, run.id)?.responseText)
				return com.plot.api.ai.provider.AgentRuntimeResult("first second")
			}
		}
		assertTrue(worker.processOne())
		val final = assertNotNull(progress.load(dev.devWorkspaceId, run.id))
		assertEquals("COMPLETE", final.phase)
		assertEquals(AgentRunStatus.SUCCEEDED, final.status)
		assertEquals("first second", final.responseText)
	}

	@Test
	fun `HTTP stream sends initial changes terminal and restores final state on reconnect`() {
		val run = runs.admit(CreateChatAgentRunRequest("Say hello"), "http-stream-${UUID.randomUUID()}")
		java.net.http.HttpClient.newHttpClient().use { client ->
			fun open() = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/api/agent-runs/${run.id}/stream")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
			val response = open()
			response.body().bufferedReader().use { reader ->
				assertEquals(200, response.statusCode())
				assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))
				val initial = readSnapshot(reader)
				val fixture = mapper.readTree(org.springframework.core.io.ClassPathResource("fixtures/chat-run-snapshot.json").inputStream.use { it.readBytes() })
				assertEquals(fixture.propertyNames().toSet(), initial.propertyNames().toSet())
				assertEquals("QUEUED", initial.path("phase").stringValue())
				val claim = assertNotNull(executions.claimNextAgentRun("http-worker", Instant.now(), Instant.now().minusSeconds(60)))
				val epoch = assertNotNull(progress.beginAgent(claim))
				progress.updateAgent(claim, epoch, "RESPONDING", "first")
				val first = readSnapshot(reader) { it.path("responseText").stringValue() == "first" }
				progress.updateAgent(claim, epoch, "RESPONDING", "first second")
				val second = readSnapshot(reader) { it.path("responseText").stringValue() == "first second" }
				assertTrue(second.path("revision").longValue() > first.path("revision").longValue())
				executions.succeedChatResponse(claim, "first second")
				assertEquals("COMPLETE", readSnapshot(reader) { it.path("status").stringValue() == "SUCCEEDED" }.path("phase").stringValue())
				assertEquals(emptyList(), reader.lineSequence().filter { it.isNotBlank() }.toList())
			}
			open().body().bufferedReader().use { reader ->
				assertEquals("first second", readSnapshot(reader).path("responseText").stringValue())
				assertEquals(emptyList(), reader.lineSequence().filter { it.isNotBlank() }.toList())
			}
		}
	}

	@Test
	fun `HTTP heartbeat and normal 45 second rotation leave server generation running`() {
		val run = runs.admit(CreateChatAgentRunRequest("Say hello"), "http-rotation-${UUID.randomUUID()}")
		java.net.http.HttpClient.newHttpClient().use { client ->
			val started = System.nanoTime()
			val response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/api/agent-runs/${run.id}/stream")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
			response.body().bufferedReader().use { reader ->
				assertEquals(200, response.statusCode())
				java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
					val lines = executor.submit<List<String>> { reader.lineSequence().toList() }.get(50, java.util.concurrent.TimeUnit.SECONDS)
					assertTrue(lines.any { it.startsWith(":") && it.contains("heartbeat") })
				}
			}
			val elapsed = java.time.Duration.ofNanos(System.nanoTime()-started).toMillis()
			assertTrue(elapsed in 43_000..50_000, "Rotation duration $elapsed")
			assertEquals(AgentRunStatus.QUEUED, progress.load(dev.devWorkspaceId, run.id)?.status)
		}
	}

	@Test
	fun `captured user membership revocation closes stream without leaking cached text`() {
		val run = runs.admit(CreateChatAgentRunRequest("Say hello"), "revoked-stream-${UUID.randomUUID()}")
		val principal = com.plot.api.common.WorkspacePrincipal(dev.devWorkspaceId, dev.devUserId)
		assertFailsWith<com.plot.api.common.ApiException> { streams.subscribe(principal.copy(workspaceId = UUID.randomUUID()), run.id) }
		assertFailsWith<com.plot.api.common.ApiException> { streams.subscribe(principal.copy(userId = UUID.randomUUID()), run.id) }
		java.net.http.HttpClient.newHttpClient().use { client ->
			val response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/api/agent-runs/${run.id}/stream")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
			response.body().bufferedReader().use { reader ->
				readSnapshot(reader)
				jdbc.update("update workspace_members set status='INACTIVE' where workspace_id=? and user_id=?", dev.devWorkspaceId, dev.devUserId)
				try {
					val error = readSnapshot(reader) { it.has("code") }
					assertEquals("ACCESS_DENIED", error.path("code").stringValue())
					assertFalse(error.has("responseText"))
					assertEquals(emptyList(), reader.lineSequence().filter { it.isNotBlank() }.toList())
					assertEquals(0, streams.activeSubscriptions)
				} finally { jdbc.update("update workspace_members set status='ACTIVE' where workspace_id=? and user_id=?", dev.devWorkspaceId, dev.devUserId) }
			}
		}
	}

	@Test
	fun `initial HTTP access failures retain 403 and 404 with SSE accept header`() {
		val run = runs.admit(CreateChatAgentRunRequest("Say hello"), "initial-denied-${UUID.randomUUID()}")
		java.net.http.HttpClient.newHttpClient().use { client ->
			fun request(id: UUID) = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/api/agent-runs/$id/stream")).header("Accept", "text/event-stream").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
			assertEquals(404, request(UUID.randomUUID()).statusCode())
			jdbc.update("update workspace_members set status='INACTIVE' where workspace_id=? and user_id=?", dev.devWorkspaceId, dev.devUserId)
			try { assertEquals(403, request(run.id).statusCode()) }
			finally { jdbc.update("update workspace_members set status='ACTIVE' where workspace_id=? and user_id=?", dev.devWorkspaceId, dev.devUserId) }
		}
	}

	@Test
	fun `source revocation closes cached snapshot and client disconnect cleans subscription`() {
		val (connection, scope) = connectedSource()
		val workspace = dev.devWorkspaceId
		val run = runs.admit(CreateChatAgentRunRequest("Say hello"), "source-stream-${UUID.randomUUID()}")
		java.net.http.HttpClient.newHttpClient().use { client ->
			fun open() = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/api/agent-runs/${run.id}/stream")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
			open().body().bufferedReader().use { reader ->
				readSnapshot(reader)
				jdbc.update("update source_scopes set status='DISABLED' where id=?", scope)
				assertEquals("ACCESS_DENIED", readSnapshot(reader) { it.has("code") }.path("code").stringValue())
				assertEquals(emptyList(), reader.lineSequence().filter { it.isNotBlank() }.toList())
			}
			jdbc.update("update source_scopes set status='ACTIVE' where id=?", scope)
			val body = open().body().bufferedReader()
			readSnapshot(body); body.close()
			jdbc.update("update source_scopes set status='DISABLED' where id=?", scope)
			val until = System.nanoTime()+2_000_000_000
			while (streams.activeSubscriptions > 0 && System.nanoTime() < until) Thread.sleep(20)
			assertEquals(0, streams.activeSubscriptions)
			assertEquals(AgentRunStatus.QUEUED, progress.load(workspace, run.id)?.status)
		}
	}

	private fun connectedSource(): Pair<UUID, UUID> {
		val connection = UUID.randomUUID(); val namespace = UUID.randomUUID(); val scope = UUID.randomUUID()
		val workspace = dev.devWorkspaceId; val user = dev.devUserId
		jdbc.update("insert into connections (id,workspace_id,provider,connection_kind,external_connection_key,status,created_by_user_id,created_at,updated_at) values (?,?,'GITHUB','GITHUB_APP_INSTALLATION',?,'ACTIVE',?,now(),now())", connection, workspace, UUID.randomUUID().toString(), user)
		jdbc.update("insert into source_namespaces (id,workspace_id,provider,namespace_kind,external_namespace_key,display_name,status,created_at,updated_at) values (?,?,'GITHUB','INSTALLATION',?,'stream source','ACTIVE',now(),now())", namespace, workspace, UUID.randomUUID().toString())
		jdbc.update("insert into connection_namespace_bindings (id,workspace_id,provider,connection_id,source_namespace_id,status,valid_from,created_at,updated_at) values (?,?,'GITHUB',?,?,'ACTIVE',now(),now(),now())", UUID.randomUUID(), workspace, connection, namespace)
		jdbc.update("insert into source_scopes (id,workspace_id,source_namespace_id,provider,scope_semantics,scope_kind,external_scope_key,external_key,display_name,status,status_changed_at,created_at,updated_at) values (?,?,?,'GITHUB','CONTAINER','REPOSITORY',?,?,'stream repo','ACTIVE',now(),now(),now())", scope, workspace, namespace, UUID.randomUUID().toString(), UUID.randomUUID().toString())
		return connection to scope
	}

	@Test
	fun `frozen retry streams after disconnect but still denies source revocation`() {
		val (connection, scope) = connectedSource()
		val original = runs.admit(CreateChatAgentRunRequest("Say hello"), "frozen-original-${UUID.randomUUID()}")
		jdbc.update("update agent_runs set status='FAILED', failure_code='TEST_FAILURE', finished_at=now() where id=?", original.id)
		val version = queries.listTurnsForSession(original.chatId).single().versions.single().id
		val retry = runs.retry(version, "frozen-retry-${UUID.randomUUID()}")
		jdbc.update("update connections set status='DISABLED' where id=?", connection)
		try {
			java.net.http.HttpClient.newHttpClient().use { client ->
				fun open() = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/api/agent-runs/${retry.agentRunId}/stream")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
				val response = open()
				response.body().bufferedReader().use { reader ->
					assertEquals(200, response.statusCode())
					readSnapshot(reader)
					val claim = assertNotNull(executions.claimNextAgentRun("frozen-stream-worker", Instant.now(), Instant.now().minusSeconds(60)))
					val epoch = assertNotNull(progress.beginAgent(claim))
					progress.updateAgent(claim, epoch, "RESPONDING", "frozen prefix")
					readSnapshot(reader) { it.path("responseText").stringValue() == "frozen prefix" }
					executions.succeedChatResponse(claim, "frozen final")
					readSnapshot(reader) { it.path("status").stringValue() == "SUCCEEDED" }
				}
				open().body().bufferedReader().use { reader ->
					assertEquals("frozen final", readSnapshot(reader).path("responseText").stringValue())
				}
				jdbc.update("update source_scopes set status='DISABLED' where id=?", scope)
				val denied = open()
				denied.body().use { assertEquals(403, denied.statusCode()) }
			}
		} finally {
			jdbc.update("update connections set status='ACTIVE' where id=?", connection)
			jdbc.update("update source_scopes set status='ACTIVE' where id=?", scope)
		}
	}

	@Test
	fun `worker coalesces a delta burst while preserving the final full response`() {
		val run = runs.admit(CreateChatAgentRunRequest("Say hello"), "burst-stream-${UUID.randomUUID()}")
		model.nativeRuntime = object : com.plot.api.ai.provider.AgentRuntime {
			override fun run(host: com.plot.api.ai.provider.AgentRuntimeHost): com.plot.api.ai.provider.AgentRuntimeResult {
				host.beforeModel()
				val started = System.nanoTime()
				repeat(100) { host.onText("안녕") }
				val elapsed = System.nanoTime()-started
				val snapshot = assertNotNull(progress.load(dev.devWorkspaceId, run.id))
				assertTrue(snapshot.revision <= 2 + elapsed / 250_000_000, "Writes coalesce at 250ms")
				host.afterModel(model.usage)
				return com.plot.api.ai.provider.AgentRuntimeResult("안녕".repeat(100))
			}
		}
		assertTrue(worker.processOne())
		assertEquals("안녕".repeat(100), progress.load(dev.devWorkspaceId, run.id)?.responseText)
	}

	@Test
	fun `heartbeat cannot discard newest snapshot while socket writers are busy`() {
		val run = runs.admit(CreateChatAgentRunRequest("Say hello"), "slow-stream-${UUID.randomUUID()}")
		java.net.http.HttpClient.newHttpClient().use { client ->
			val response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/api/agent-runs/${run.id}/stream")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
			response.body().bufferedReader().use { reader ->
				readSnapshot(reader)
				// Controlled socket-writer saturation; assertions observe only actual HTTP output.
				val field = ChatRunStreamService::class.java.getDeclaredField("writers").apply { isAccessible = true }
				val pool = field.get(streams) as java.util.concurrent.ThreadPoolExecutor
				val occupied = java.util.concurrent.CountDownLatch(pool.maximumPoolSize)
				val release = java.util.concurrent.CountDownLatch(1)
				try {
					repeat(pool.maximumPoolSize) { pool.execute { occupied.countDown(); release.await(25, java.util.concurrent.TimeUnit.SECONDS) } }
					assertTrue(occupied.await(2, java.util.concurrent.TimeUnit.SECONDS))
					val claim = assertNotNull(executions.claimNextAgentRun("slow-worker", Instant.now(), Instant.now().minusSeconds(60)))
					val epoch = assertNotNull(progress.beginAgent(claim))
					progress.updateAgent(claim, epoch, "RESPONDING", "latest snapshot")
					Thread.sleep(16_000) // Real heartbeat interval; no fake private clock/state.
				} finally { release.countDown() }
				assertEquals("latest snapshot", readSnapshot(reader) { it.path("responseText").stringValue() == "latest snapshot" }.path("responseText").stringValue())
			}
		}
	}

	private fun readSnapshot(reader: java.io.BufferedReader, matches: (tools.jackson.databind.JsonNode) -> Boolean = { true }): tools.jackson.databind.JsonNode =
		java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
			val future = executor.submit<tools.jackson.databind.JsonNode> {
				while (true) {
					val line = reader.readLine() ?: error("Stream ended before expected snapshot")
					if (line.startsWith("data:")) {
						val snapshot = mapper.readTree(line.removePrefix("data:").trim())
						if (matches(snapshot)) return@submit snapshot
					}
				}
				error("Unreachable")
			}
			try { future.get(5, java.util.concurrent.TimeUnit.SECONDS) }
			catch (failure: Exception) { future.cancel(true); reader.close(); throw failure }
		}

	private fun admitAndClaim(): ClaimedAgentRun {
		runs.admit(CreateChatAgentRunRequest("Say hello"), "stream-test-${UUID.randomUUID()}")
		return assertNotNull(executions.claimNextAgentRun("test-worker", Instant.now(), Instant.now().minusSeconds(60)))
	}
}
