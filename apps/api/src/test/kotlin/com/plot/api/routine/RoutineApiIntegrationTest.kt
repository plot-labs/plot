package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.routines.poll-delay=PT1H",
	"plot.routine-agent.workers-enabled=true",
	"server.address=127.0.0.1",
])
class RoutineApiIntegrationTest {
	@Autowired private lateinit var mockMvc: MockMvc
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var agentPersistence: RoutineAgentPersistence
	@Autowired private lateinit var routinePersistence: RoutinePersistence
	@Autowired private lateinit var objectMapper: ObjectMapper

	@BeforeEach
	fun clearRoutinesAndSources() {
		devBootstrapService.bootstrap()
		jdbcTemplate.update("delete from content_packs where generation_run_id in (select id from generation_runs where workspace_id = ? and agent_run_id is not null)", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from agent_steps where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from generation_runs where workspace_id = ? and agent_run_id is not null", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from agent_runs where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from routine_executions where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from routines where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_namespaces where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connections where workspace_id = ?", devContext.devWorkspaceId)
	}

	@Test
	fun `routine create stores distinct active context sources`() {
		val triggerSourceId = insertSourceScope("acme/plot")
		val contextSourceId = insertSourceScope("acme/docs")

		mockMvc.post("/api/routines") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{"name":"Release context","sourceScopeId":"$triggerSourceId","contextSourceScopeIds":["$contextSourceId"],"instruction":"Draft with context","cadence":"DAILY"}
			""".trimIndent()
		}.andExpect {
			status { isCreated() }
			jsonPath("$.sourceScopeId") { value(triggerSourceId.toString()) }
			jsonPath("$.contextSourceScopeIds[0]") { value(contextSourceId.toString()) }
		}

		val routineId = jdbcTemplate.queryForObject(
			"select id from routines where workspace_id = ? and source_scope_id = ?",
			UUID::class.java,
			devContext.devWorkspaceId,
			triggerSourceId,
		)!!
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from routine_context_sources where workspace_id = ? and routine_id = ? and source_scope_id = ?",
			Int::class.java,
			devContext.devWorkspaceId,
			routineId,
			contextSourceId,
		))

		mockMvc.post("/api/routines") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Invalid","sourceScopeId":"$triggerSourceId","contextSourceScopeIds":["$triggerSourceId"],"instruction":"Draft","cadence":"DAILY"}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.error") { value("INVALID_CONTEXT_SOURCES") }
		}

		jdbcTemplate.update(
			"update connections set status = 'DISABLED', updated_at = now() where id = (select binding.connection_id from connection_namespace_bindings binding join source_scopes scope on scope.workspace_id = binding.workspace_id and scope.source_namespace_id = binding.source_namespace_id where scope.id = ?)",
			contextSourceId,
		)
		mockMvc.post("/api/routines") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Inactive context","sourceScopeId":"$triggerSourceId","contextSourceScopeIds":["$contextSourceId"],"instruction":"Draft","cadence":"DAILY"}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("SOURCE_NOT_READY") }
		}
	}

	@Test
	fun `routine lifecycle stays scoped to the dev workspace`() {
		val sourceScopeId = insertSourceScope()

		mockMvc.post("/api/routines") {
			contentType = MediaType.APPLICATION_JSON
			content = """
				{"name":"Weekly release note","sourceScopeId":"$sourceScopeId","instruction":"Draft a release note","cadence":"WEEKLY"}
			""".trimIndent()
		}.andExpect {
			status { isCreated() }
			jsonPath("$.name") { value("Weekly release note") }
			jsonPath("$.sourceScopeId") { value(sourceScopeId.toString()) }
			jsonPath("$.sourceLabel") { value("acme/plot") }
			jsonPath("$.enabled") { value(true) }
		}

		val routineId = jdbcTemplate.queryForObject(
			"select id from routines where workspace_id = ? and source_scope_id = ?",
			UUID::class.java,
			devContext.devWorkspaceId,
			sourceScopeId,
		)
		val scheduledNextRunAt = jdbcTemplate.queryForObject(
			"select next_run_at from routines where id = ?",
			Timestamp::class.java,
			routineId,
		)

		mockMvc.get("/api/routines")
			.andExpect {
				status { isOk() }
				jsonPath("$[0].id") { value(routineId.toString()) }
				jsonPath("$[0].cadence") { value("WEEKLY") }
			}

		mockMvc.patch("/api/routines/$routineId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"enabled":false}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.enabled") { value(false) }
				jsonPath("$.cadence") { value("WEEKLY") }
			}

		mockMvc.post("/api/routines/$routineId/run")
			.andExpect { status { isBadRequest() } }
		mockMvc.post("/api/routines/$routineId/run") {
			headers { add("Idempotency-Key", "manual-lifecycle") }
		}
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(routineId.toString()) }
				jsonPath("$.lastRunStatus") { value("NO_ACTIVITY") }
				jsonPath("$.latestExecution.status") { value("NO_ACTIVITY") }
			}

		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from routine_executions where routine_id = ? and trigger_key = ?",
			Int::class.java,
			routineId,
			"manual:$routineId:manual-lifecycle",
		))
		mockMvc.post("/api/routines/$routineId/run") {
			headers { add("Idempotency-Key", "manual-lifecycle") }
		}
			.andExpect {
				status { isOk() }
				jsonPath("$.id") { value(routineId.toString()) }
				jsonPath("$.lastRunStatus") { value("NO_ACTIVITY") }
			}
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from routine_executions where routine_id = ? and trigger_key = ?",
			Int::class.java,
			routineId,
			"manual:$routineId:manual-lifecycle",
		))
		assertEquals(scheduledNextRunAt, jdbcTemplate.queryForObject(
			"select next_run_at from routines where id = ?",
			Timestamp::class.java,
			routineId,
		))
	}

	@Test
	fun `concurrent manual retries return one execution identity`() {
		val routineId = createRoutine(insertSourceScope())
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val responses = (1..2).map {
				executor.submit<String> {
					start.await(5, TimeUnit.SECONDS)
					mockMvc.post("/api/routines/$routineId/run") {
						headers { add("Idempotency-Key", "same-request") }
					}.andReturn().also { result -> assertEquals(200, result.response.status) }
						.response.contentAsString
				}
			}
			start.countDown()
			val executionIds = responses.map { future ->
				objectMapper.readTree(future.get(10, TimeUnit.SECONDS))
					.get("latestExecution").get("id").asText()
			}
			assertEquals(1, executionIds.toSet().size)
			assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from routine_executions where routine_id = ? and trigger_key = ?",
				Int::class.java,
				routineId,
				"manual:$routineId:same-request",
			))
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `routine and agent detail expose safe status and Artifact links`() {
		val sourceScopeId = insertSourceScope()
		val routineId = createRoutine(sourceScopeId)
		val execution = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = devContext.devWorkspaceId,
				routineId = routineId,
				createdByUserId = devContext.devUserId,
				triggerSourceScopeId = sourceScopeId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "manual:$routineId:safe-detail",
				requestFingerprint = "safe-detail:$routineId",
			),
		)
		val chatId = UUID.randomUUID()
		val agentRunId = UUID.randomUUID()
		val generationRunId = UUID.randomUUID()
		val artifactId = UUID.randomUUID()
		val now = Timestamp.from(Instant.parse("2026-08-10T00:00:00Z"))
		jdbcTemplate.update(
			"insert into work_sessions (id, workspace_id, title, status, created_by_user_id, last_activity_at, created_at, updated_at, routine_execution_id) values (?, ?, 'Routine: Safe detail', 'OPEN', ?, ?, ?, ?, ?)",
			chatId,
			devContext.devWorkspaceId,
			devContext.devUserId,
			now,
			now,
			now,
			execution.id,
		)
		jdbcTemplate.update(
			"""
			insert into agent_runs (
			 id, workspace_id, routine_execution_id, routine_id, work_session_id, created_by_user_id,
			 instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot,
			 status, current_step, attempt_count, max_attempts, model_call_count, tool_call_count,
			 started_at, finished_at, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, 'Draft safely', 'v1', 'read-only-v1', '{}'::jsonb,
			 'SUCCEEDED', 2, 0, 3, 1, 1, ?, ?, ?, ?)
			""".trimIndent(),
			agentRunId,
			devContext.devWorkspaceId,
			execution.id,
			routineId,
			chatId,
			devContext.devUserId,
			now,
			now,
			now,
			now,
		)
		jdbcTemplate.update(
			"""
			insert into generation_runs (
			 id, workspace_id, created_by_user_id, idempotency_key, request_fingerprint,
			 status, workflow_version, prompt_version, output_schema_version, budget_version,
			 provider, model_name, budget_snapshot, user_instruction, work_session_id, agent_run_id,
			 finished_at, created_at, updated_at
			) values (?, ?, ?, ?, ?, 'READY', 'v1', 'v1', 'v1', 'v1',
			 'test', 'test', '{}'::jsonb, 'Draft safely', ?, ?, ?, ?, ?)
			""".trimIndent(),
			generationRunId,
			devContext.devWorkspaceId,
			devContext.devUserId,
			"agent:$agentRunId",
			"agent:$agentRunId",
			chatId,
			agentRunId,
			now,
			now,
			now,
		)
		jdbcTemplate.update(
			"insert into content_packs (id, workspace_id, generation_run_id, title, status, created_at, updated_at) values (?, ?, ?, 'Routine Artifact', 'READY', ?, ?)",
			artifactId,
			devContext.devWorkspaceId,
			generationRunId,
			now,
			now,
		)
		jdbcTemplate.update(
			"""
			insert into agent_steps (
			 id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
			 tool_name, arguments, result, started_at, finished_at, created_at
			) values (?, ?, ?, 0, 'READ_TOOL', 'SUCCEEDED', ?, 'READ_WRITING_BLOCKS',
			 '{"authorization":"super-secret"}'::jsonb, '{"rawPayload":"super-secret"}'::jsonb, ?, ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			agentRunId,
			"agent:$agentRunId:step:0",
			now,
			now,
			now,
		)
		jdbcTemplate.update(
			"""
			insert into agent_steps (
			 id, workspace_id, agent_run_id, sequence, step_kind, status, idempotency_key,
			 generation_run_id, arguments, result, started_at, finished_at, created_at
			) values (?, ?, ?, 1, 'ARTIFACT_HANDOFF', 'SUCCEEDED', ?, ?, '{}'::jsonb,
			 '{"summary":"Created an Artifact draft"}'::jsonb, ?, ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			agentRunId,
			"agent:$agentRunId:step:1",
			generationRunId,
			now,
			now,
			now,
		)
		jdbcTemplate.update(
			"update routine_executions set status = 'DISPATCHED', started_at = ?, finished_at = ?, updated_at = ? where id = ?",
			now,
			now,
			now,
			execution.id,
		)
		jdbcTemplate.update(
			"update routines set last_execution_id = ?, last_run_status = 'QUEUED', updated_at = ? where id = ?",
			execution.id,
			now,
			routineId,
		)

		mockMvc.get("/api/routines/$routineId").andExpect {
			status { isOk() }
			jsonPath("$.latestExecution.id") { value(execution.id.toString()) }
			jsonPath("$.latestExecution.agentRunStatus") { value("SUCCEEDED") }
			jsonPath("$.latestExecution.artifactId") { value(artifactId.toString()) }
			jsonPath("$.latestExecution.chatId") { value(chatId.toString()) }
		}
		val detail = mockMvc.get("/api/routines/$routineId/agent-runs/$agentRunId").andExpect {
			status { isOk() }
			jsonPath("$.artifactId") { value(artifactId.toString()) }
			jsonPath("$.chatId") { value(chatId.toString()) }
			jsonPath("$.steps[0].sequence") { value(0) }
			jsonPath("$.steps[1].generationRunId") { value(generationRunId.toString()) }
			jsonPath("$.steps[1].artifactId") { value(artifactId.toString()) }
			jsonPath("$.claimedBy") { doesNotExist() }
			jsonPath("$.steps[0].arguments") { doesNotExist() }
			jsonPath("$.steps[0].result") { doesNotExist() }
		}.andReturn().response.contentAsString
		assertFalse(detail.contains("super-secret"))
		assertFalse(detail.contains("authorization", ignoreCase = true))
		assertFalse(detail.contains("rawPayload"))
	}

	@Test
	fun `foreign workspace routine and agent details reveal no identifiers`() {
		val foreignWorkspaceId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into workspaces (id, name, slug, created_by_user_id, status, plan, entitlement_status, access_mode, created_at, updated_at) values (?, 'Foreign', ?, ?, 'ACTIVE', 'founding', 'active', 'full', now(), now())",
			foreignWorkspaceId,
			"foreign-${UUID.randomUUID()}",
			devContext.devUserId,
		)
		val foreignSourceId = insertSourceScope("foreign/repo", foreignWorkspaceId)
		val foreignRoutine = routinePersistence.insert(
			foreignWorkspaceId,
			devContext.devUserId,
			"Foreign Routine",
			foreignSourceId,
			"Private instruction",
			RoutineCadence.DAILY,
		)
		val foreignExecution = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = foreignWorkspaceId,
				routineId = foreignRoutine.id,
				createdByUserId = devContext.devUserId,
				triggerSourceScopeId = foreignSourceId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "manual:${foreignRoutine.id}:foreign",
				requestFingerprint = "foreign:${foreignRoutine.id}",
			),
		)
		val foreignAgentRunId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
			insert into agent_runs (
			 id, workspace_id, routine_execution_id, routine_id, created_by_user_id,
			 instruction_snapshot, prompt_version, tool_policy_version, budget_snapshot,
			 status, current_step, attempt_count, max_attempts, model_call_count, tool_call_count,
			 created_at, updated_at
			) values (?, ?, ?, ?, ?, 'Foreign secret', 'v1', 'read-only-v1', '{}'::jsonb,
			 'QUEUED', 0, 0, 3, 0, 0, now(), now())
			""".trimIndent(),
			foreignAgentRunId,
			foreignWorkspaceId,
			foreignExecution.id,
			foreignRoutine.id,
			devContext.devUserId,
		)

		val routineBody = mockMvc.get("/api/routines/${foreignRoutine.id}").andExpect {
			status { isNotFound() }
		}.andReturn().response.contentAsString
		val agentBody = mockMvc.get("/api/routines/${foreignRoutine.id}/agent-runs/$foreignAgentRunId").andExpect {
			status { isNotFound() }
		}.andReturn().response.contentAsString
		assertTrue(routineBody.contains("NOT_FOUND"))
		assertTrue(agentBody.contains("NOT_FOUND"))
		assertFalse(routineBody.contains(foreignRoutine.id.toString()))
		assertFalse(agentBody.contains(foreignAgentRunId.toString()))
		assertFalse(agentBody.contains("Foreign secret"))
	}

	@Test
	fun `active claims fence updates and manual runs`() {
		val sourceScopeId = insertSourceScope()
		val routineId = createRoutine(sourceScopeId)
		jdbcTemplate.update(
			"""
			update routines
			set claimed_by = ?, claimed_at = now(), active_execution_id = ?,
			    transition_version = transition_version + 1
			where id = ?
			""".trimIndent(),
			"routine-github:${UUID.randomUUID()}",
			UUID.randomUUID(),
			routineId,
		)

		mockMvc.patch("/api/routines/$routineId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"enabled":false}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("ROUTINE_BUSY") }
		}
		mockMvc.post("/api/routines/$routineId/run") {
			headers { add("Idempotency-Key", "manual-busy") }
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("ROUTINE_BUSY") }
		}
	}

	@Test
	fun `disconnected routine can be disabled and a manual run reports source failure`() {
		val sourceScopeId = insertSourceScope()
		val routineId = createRoutine(sourceScopeId)
		jdbcTemplate.update("update source_scopes set status = 'ERROR', updated_at = now() where id = ?", sourceScopeId)

		mockMvc.patch("/api/routines/$routineId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"enabled":false}"""
		}.andExpect {
			status { isOk() }
			jsonPath("$.enabled") { value(false) }
		}
		mockMvc.patch("/api/routines/$routineId") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"enabled":true}"""
		}.andExpect {
			status { isConflict() }
			jsonPath("$.error") { value("SOURCE_NOT_READY") }
		}
		assertEquals(false, jdbcTemplate.queryForObject(
			"select enabled from routines where id = ?",
			Boolean::class.java,
			routineId,
		))
		mockMvc.post("/api/routines/$routineId/run") {
			headers { add("Idempotency-Key", "manual-source-failure") }
		}.andExpect {
			status { isOk() }
			jsonPath("$.lastRunStatus") { value("FAILED") }
			jsonPath("$.lastErrorCode") { value("SOURCE_NOT_READY") }
		}
	}

	@Test
	fun `github event cadence can be configured without becoming scheduler work`() {
		val sourceScopeId = insertSourceScope()

		mockMvc.post("/api/routines") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Release update","sourceScopeId":"$sourceScopeId","instruction":"Draft the release","cadence":"ON_GITHUB_RELEASE"}"""
		}.andExpect {
			status { isCreated() }
			jsonPath("$.cadence") { value("ON_GITHUB_RELEASE") }
		}

		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from routines where workspace_id = ? and cadence in ('DAILY', 'WEEKLY') and next_run_at <= now()",
				Int::class.java,
				devContext.devWorkspaceId,
			),
		)
	}

	private fun insertSourceScope(
		label: String = "acme/plot",
		workspaceId: UUID = devContext.devWorkspaceId,
	): UUID {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())",
			connectionId,
			workspaceId,
			(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).toString(),
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			workspaceId,
			"installation-${UUID.randomUUID()}",
			label.substringBefore('/'),
		)
		jdbcTemplate.update(
			"insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at) values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())",
			bindingId,
			workspaceId,
			connectionId,
			namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, external_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			workspaceId,
			namespaceId,
			"repository-${UUID.randomUUID()}",
			label,
			label,
		)
		return scopeId
	}

	private fun createRoutine(sourceScopeId: UUID): UUID {
		mockMvc.post("/api/routines") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"Weekly update","sourceScopeId":"$sourceScopeId","instruction":"Draft an update","cadence":"WEEKLY"}"""
		}.andExpect { status { isCreated() } }
		return jdbcTemplate.queryForObject(
			"select id from routines where workspace_id = ? and source_scope_id = ?",
			UUID::class.java,
			devContext.devWorkspaceId,
			sourceScopeId,
		)!!
	}
}
