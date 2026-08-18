package com.plot.api.routine

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import com.plot.api.routine.dto.CreateChatAgentRunRequest
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["plot.dev-bootstrap.enabled=true", "server.address=127.0.0.1"])
class AgentExecutionContractIntegrationTest {
	@Autowired
	private lateinit var mockMvc: MockMvc

	@Autowired
	private lateinit var chatAdmission: ChatAgentAdmissionService

	@Autowired
	private lateinit var jdbcTemplate: JdbcTemplate

	@Autowired
	private lateinit var devContext: DevContext


	@Test
	fun `Chat admission creates an AgentRun before artifact generation`() {
		val before = jdbcTemplate.queryForObject(
			"select count(*) from agent_runs where workspace_id = ?",
			Long::class.java,
			devContext.devWorkspaceId,
		) ?: 0
		insertSourceScope()

		val response = chatAdmission.admit(
			CreateChatAgentRunRequest("Contract guard"),
			"contract-${UUID.randomUUID()}",
		)

		assertEquals(before + 1, jdbcTemplate.queryForObject(
			"select count(*) from agent_runs where workspace_id = ?",
			Long::class.java,
			devContext.devWorkspaceId,
		))
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from generation_runs where workspace_id = ? and agent_run_id = ?",
			Long::class.java,
			devContext.devWorkspaceId,
			response.id,
		))
	}
	@Test
	fun `legacy direct artifact execution route is no longer public`() {
		mockMvc.post("/api/generations")
			.andExpect { status { isNotFound() } }
	}

	@Test
	fun `Chat activity is exposed through Agent runs`() {
		mockMvc.get("/api/sessions/${UUID.randomUUID()}/agent-runs")
			.andExpect { status { isNotFound() } }
	}

	private fun insertSourceScope() {
		val connectionId = UUID.randomUUID()
		val namespaceId = UUID.randomUUID()
		val bindingId = UUID.randomUUID()
		val scopeId = UUID.randomUUID()
		jdbcTemplate.update(
			"insert into connections (id, workspace_id, provider, connection_kind, external_connection_key, status, created_by_user_id, created_at, updated_at) values (?, ?, 'GITHUB', 'GITHUB_APP_INSTALLATION', ?, 'ACTIVE', ?, now(), now())",
			connectionId,
			devContext.devWorkspaceId,
			"contract-${UUID.randomUUID()}",
			devContext.devUserId,
		)
		jdbcTemplate.update(
			"""
			insert into source_namespaces
			(id, workspace_id, provider, namespace_kind, external_namespace_key, display_name, status, created_at, updated_at)
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, 'acme', 'ACTIVE', now(), now())
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
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, 'acme/plot', 'ACTIVE', now(), now())
			""".trimIndent(),
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			"repository-${UUID.randomUUID()}",
			"acme/plot",
		)
	}

}
