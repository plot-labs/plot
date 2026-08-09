package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import java.sql.Timestamp
import java.util.UUID
import kotlin.test.assertEquals
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

	@BeforeEach
	fun clearRoutinesAndSources() {
		devBootstrapService.bootstrap()
		jdbcTemplate.update("delete from routine_executions where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from routines where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_scopes where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connection_namespace_bindings where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from source_namespaces where workspace_id = ?", devContext.devWorkspaceId)
		jdbcTemplate.update("delete from connections where workspace_id = ?", devContext.devWorkspaceId)
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

	private fun insertSourceScope(): UUID {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())",
			connectionId,
			devContext.devWorkspaceId,
			(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).toString(),
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'Acme', 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			devContext.devWorkspaceId,
			"installation-${UUID.randomUUID()}",
		)
		jdbcTemplate.update(
			"insert into connection_namespace_bindings (id, workspace_id, provider, connection_id, source_namespace_id, status, valid_from, created_at, updated_at) values (?, ?, 'GITHUB', ?, ?, 'ACTIVE', now(), now(), now())",
			bindingId,
			devContext.devWorkspaceId,
			connectionId,
			namespaceId,
		)
		jdbcTemplate.update(
			"""
			insert into source_scopes
			(id, workspace_id, source_namespace_id, provider, scope_semantics, scope_kind,
			 external_scope_key, external_key, display_name, status, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, 'acme/plot', 'acme/plot', 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			"repository-${UUID.randomUUID()}",
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
