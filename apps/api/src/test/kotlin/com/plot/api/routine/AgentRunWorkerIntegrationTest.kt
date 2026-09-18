package com.plot.api.routine

import com.plot.api.agent.AgentRunExecutionPersistence
import com.plot.api.agent.ArtifactAutomationAdmissionService
import com.plot.api.agent.AgentRunWorker
import com.plot.api.agent.AgentStepKind
import com.plot.api.agent.AgentStepRequest
import com.plot.api.agent.AgentStepStatus

import com.plot.api.TestcontainersConfiguration
import com.plot.api.ai.provider.AgentDecision
import com.plot.api.ai.provider.AgentDecisionAction
import com.plot.api.ai.provider.AgentDecisionException
import com.plot.api.ai.provider.AgentRuntime
import com.plot.api.ai.provider.AgentRuntimeHost
import com.plot.api.ai.provider.AgentDecisionRequest
import com.plot.api.ai.provider.AgentResponseMode
import com.plot.api.ai.provider.ArtifactWorkflowModelGateway
import com.plot.api.ai.provider.ModelCallMetadata
import com.plot.api.ai.provider.ModelCallResult
import com.plot.api.ai.provider.ReviewerModelRequest
import com.plot.api.ai.provider.RewriteModelRequest
import com.plot.api.ai.provider.WriterModelRequest
import com.plot.api.artifact.workflow.ArtifactWorkflowRunDispatcher
import com.plot.api.artifact.workflow.ArtifactWorkflowRunWorker
import com.plot.api.artifact.workflow.model.ReviewVerdict
import com.plot.api.artifact.workflow.model.ReviewerOutput
import com.plot.api.artifact.workflow.model.SentenceReview
import com.plot.api.artifact.workflow.model.TargetedRewriteOutput
import com.plot.api.artifact.workflow.model.WriterOutput
import com.plot.api.artifact.workflow.model.WriterSentence
import com.plot.api.chat.ChatQueryService
import com.plot.api.chat.ChatRunService
import com.plot.api.chat.dto.CreateChatAgentRunRequest
import com.plot.api.common.ApiException
import com.plot.api.common.WorkspacePrincipal
import com.plot.api.config.PlotAiProperties
import com.plot.api.billing.PolarCreditProvider
import com.plot.api.billing.PolarCustomer
import com.plot.api.billing.PolarEventResult
import com.plot.api.ai.provider.ProviderUsage
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.worksession.WorkSessionService
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@Import(TestcontainersConfiguration::class, AgentRunWorkerIntegrationTest.Config::class)
@ActiveProfiles("test")
@TestPropertySource(properties = [
	"plot.dev-bootstrap.enabled=true",
	"plot.routines.schedule-scan-delay=PT1H",
	"plot.routine-agent.workers-enabled=true",
	"plot.routine-agent.auto-dispatch-enabled=false",
	"plot.routine-agent.claim-timeout=PT1S",
	"plot.routine-agent.retry-initial-delay=PT0S",
	"plot.routine-agent.max-attempts=2",
	"plot.routine-agent.max-model-calls=3",
	"plot.routine-agent.max-tool-calls=2",
	"plot.polar.credits-enabled=true",
	"plot.polar.access-token=test-token",
	"plot.polar.ai-meter-id=test-meter",
	"server.address=127.0.0.1",
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentRunWorkerIntegrationTest {
	@Autowired private lateinit var devBootstrapService: DevBootstrapService
	@Autowired private lateinit var devContext: DevContext
	@Autowired private lateinit var jdbcTemplate: JdbcTemplate
	@Autowired private lateinit var routinePersistence: RoutinePersistence
	@Autowired private lateinit var agentPersistence: RoutineAgentPersistence
	@Autowired private lateinit var agentRunExecutionPersistence: AgentRunExecutionPersistence
	@Autowired private lateinit var routineWorker: RoutineWorker
	@Autowired private lateinit var agentWorker: AgentRunWorker
	@Autowired private lateinit var artifactWorkflowWorker: ArtifactWorkflowRunWorker
	@Autowired private lateinit var chatAdmission: ChatRunService
	@Autowired private lateinit var automationAdmission: ArtifactAutomationAdmissionService
	@Autowired private lateinit var chatQueries: ChatQueryService
	@Autowired private lateinit var workSessions: WorkSessionService
	@Autowired private lateinit var agentModel: ScriptedAgentRuntime
	@Autowired private lateinit var artifactWorkflowModel: AgentArtifactWorkflowModelGateway
	@Autowired private lateinit var polarCredits: AgentPolarCreditProvider

	@BeforeEach
	fun isolateScenario() {
		devBootstrapService.bootstrap()
		jdbcTemplate.update(
			"""
			update agent_runs
			set status = 'FAILED', failure_code = 'TEST_ISOLATION', claimed_by = null, claimed_at = null,
			    next_attempt_at = null, finished_at = coalesce(finished_at, greatest(now(), coalesce(started_at, created_at))), updated_at = now()
			where status in ('QUEUED', 'RUNNING')
			""".trimIndent(),
		)
		jdbcTemplate.update(
			"""
			update generation_runs
			set status = 'FAILED', error_code = 'TEST_ISOLATION', claimed_by = null, claimed_at = null,
			    heartbeat_at = null, next_attempt_at = null, finished_at = coalesce(finished_at, greatest(now(), coalesce(started_at, created_at))), updated_at = now()
			where status in ('QUEUED', 'WRITING', 'REVIEWING', 'REWRITING')
			""".trimIndent(),
		)
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = 'active', access_mode = 'full', updated_at = now() where id = ?",
			devContext.devWorkspaceId,
		)
		agentModel.reset()
		artifactWorkflowModel.reset()
		polarCredits.reset()
	}

	@Test
	fun `agent reads two allowed sources then hands immutable evidence to one Artifact`() {
		val trigger = insertSource("acme/plot")
		val context = insertSource("acme/docs")
		val triggerBlock = insertBlock(trigger, "Trigger context", "Original trigger body")
		val contextBlock = insertBlock(context, "Docs context", "Original docs body")
		val routine = routinePersistence.insert(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			name = "Multi-source update",
			sourceScopeId = trigger.scopeId,
			instruction = "Create a cited update from the relevant sources",
			cadence = RoutineCadence.DAILY,
            skillsSnapshotJson = tools.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(listOf(
                com.plot.api.skill.SkillSnapshot(UUID.randomUUID(), "benefits", "Customer benefits", "Focus on customer benefits.", 1),
            )),
		)
		agentPersistence.addContextSource(routine.workspaceId, routine.id, context.scopeId, 0)
		val execution = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = routine.workspaceId,
				routineId = routine.id,
				createdByUserId = routine.createdByUserId,
				triggerSourceScopeId = trigger.scopeId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "manual:${routine.id}:agent-e2e",
				requestFingerprint = "agent-e2e:${routine.id}",
				activityCursorBefore = routine.activityCursorSequence,
			),
		)
		routineWorker.runNow(routine.workspaceId, routine.id, execution.id)
		val agentRunId = jdbcTemplate.queryForObject(
			"select id from agent_runs where workspace_id = ? and routine_execution_id = ?",
			UUID::class.java,
			routine.workspaceId,
			execution.id,
		)!!
		assertEquals(null, routinePersistence.find(routine.workspaceId, routine.id)?.activityCursorSequence)
		agentModel.reads = listOf(trigger.scopeId to triggerBlock, context.scopeId to contextBlock)

		assertTrue(agentWorker.processOne())
		assertTrue(agentWorker.processOne())
		jdbcTemplate.update(
			"update writing_blocks set title = 'MUTATED', body = 'MUTATED SECRET', updated_at = now() where id in (?, ?)",
			triggerBlock,
			contextBlock,
		)
		assertTrue(agentWorker.processOne())

		val artifactWorkflowRunId = jdbcTemplate.queryForObject(
			"select id from generation_runs where workspace_id = ? and agent_run_id = ?",
			UUID::class.java,
			routine.workspaceId,
			agentRunId,
		)!!
		assertEquals(1, count("select count(*) from artifact_runs where workspace_id = ? and agent_run_id = ?", routine.workspaceId, agentRunId))
		assertEquals(1, count(
			"select count(*) from generation_runs where workspace_id = ? and artifact_run_id = (select id from artifact_runs where workspace_id = ? and agent_run_id = ?)",
			routine.workspaceId, routine.workspaceId, agentRunId,
		))
		assertEquals(2, count("select count(*) from agent_steps where agent_run_id = ? and step_kind = 'READ_TOOL'", agentRunId))
		assertEquals(1, count("select count(*) from agent_steps where agent_run_id = ? and step_kind = 'ARTIFACT_HANDOFF'", agentRunId))
		assertEquals(2, count("select count(*) from generation_inputs where generation_run_id = ?", artifactWorkflowRunId))
		assertEquals(2, count("select count(distinct source_scope_id) from generation_inputs where generation_run_id = ?", artifactWorkflowRunId))
		assertEquals(2, count("select count(*) from generation_inputs where generation_run_id = ? and agent_run_input_id is not null", artifactWorkflowRunId))
		assertEquals(0, count(
			"""
			select count(*)
			from generation_inputs generation_input
			join agent_run_inputs agent_input
			  on agent_input.workspace_id = generation_input.workspace_id
			 and agent_input.id = generation_input.agent_run_input_id
			where generation_input.generation_run_id = ?
			  and generation_input.source_scope_id is distinct from agent_input.source_scope_id
			""".trimIndent(),
			artifactWorkflowRunId,
		))
		assertEquals(1, count(
			"select count(*) from generation_runs where id = ? and work_session_id is not null",
			artifactWorkflowRunId,
		))
		assertEquals(1, count(
			"""
			select count(*)
			from generation_runs generation
			join agent_runs agent
			  on agent.workspace_id = generation.workspace_id
			 and agent.id = generation.agent_run_id
			 and agent.work_session_id = generation.work_session_id
			where generation.id = ?
			""".trimIndent(),
			artifactWorkflowRunId,
		))
		assertEquals(
			listOf("Original trigger body", "Original docs body"),
			jdbcTemplate.query(
				"select snapshot_body from generation_inputs where generation_run_id = ? order by order_index",
				{ rs, _ -> rs.getString(1) },
				artifactWorkflowRunId,
			),
		)
		assertEquals(null, jdbcTemplate.queryForObject(
			"select source_scope_id from generation_runs where id = ?",
			UUID::class.java,
			artifactWorkflowRunId,
		))

		assertEquals(2, artifactWorkflowWorker.drain())
		assertEquals("SUCCEEDED", jdbcTemplate.queryForObject("select status from agent_runs where id = ?", String::class.java, agentRunId))
		assertEquals("READY", jdbcTemplate.queryForObject("select status from artifact_runs where workspace_id = ? and agent_run_id = ?", String::class.java, routine.workspaceId, agentRunId))
		routinePersistence.find(routine.workspaceId, routine.id)!!.also { projected ->
			assertEquals(artifactWorkflowRunId, projected.lastArtifactWorkflowRunId)
			assertEquals("READY", projected.lastRunStatus)
			assertEquals(null, projected.lastErrorCode)
			assertEquals(null, projected.activeExecutionId)
		}
		assertEquals(
			jdbcTemplate.queryForObject(
				"select activity_sequence from agent_run_inputs where agent_run_id = ? and writing_block_id = ? and input_kind = 'SEED'",
				Long::class.java,
				agentRunId,
				triggerBlock,
			),
			routinePersistence.find(routine.workspaceId, routine.id)?.activityCursorSequence,
		)
		assertEquals(1, count("select count(*) from content_packs where generation_run_id = ?", artifactWorkflowRunId))
		val routineChatId = jdbcTemplate.queryForObject(
			"select work_session_id from agent_runs where id = ?",
			UUID::class.java,
			agentRunId,
		)!!
		val historyRuns = chatQueries.listForSession(routineChatId)
		assertEquals(listOf(agentRunId), historyRuns.map { it.id })
		assertNotNull(historyRuns.single().artifactId)
		assertEquals(3, agentModel.requests.size)
        assertTrue(agentModel.requests.none { it.instruction.contains("Focus on customer benefits.") })
        assertTrue(artifactWorkflowModel.writerRequests.single().instruction.orEmpty().contains("Focus on customer benefits."))
		assertTrue(agentModel.requests.none { request ->
			request.toString().contains("MUTATED SECRET") || request.toString().contains("Authorization")
		})
		assertOwnershipAuditClear()
	}

	@Test
	fun `routine freezes the selected model and reasoning effort for its Agent run`() {
		val source = insertSource("acme/model-selection")
		val blockId = insertBlock(source, "Activity", "Selected model evidence")
		val routine = routinePersistence.insert(
			workspaceId = devContext.devWorkspaceId,
			createdByUserId = devContext.devUserId,
			name = "Selected model routine",
			sourceScopeId = source.scopeId,
			instruction = "Create an update with the selected model",
			cadence = RoutineCadence.DAILY,
			model = PlotAiProperties.GEMINI_3_8_FLASH_MODEL,
			reasoningEffort = "high",
		)
		val execution = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = routine.workspaceId,
				routineId = routine.id,
				createdByUserId = routine.createdByUserId,
				triggerSourceScopeId = routine.sourceScopeId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "manual:${routine.id}:selected-model",
				requestFingerprint = "selected-model:${routine.id}",
				activityCursorBefore = routine.activityCursorSequence,
			),
		)

		routineWorker.runNow(routine.workspaceId, routine.id, execution.id)
		val agentRunId = jdbcTemplate.queryForObject(
			"select id from agent_runs where workspace_id = ? and routine_execution_id = ?",
			UUID::class.java,
			routine.workspaceId,
			execution.id,
		)!!
		assertEquals(
			PlotAiProperties.GEMINI_3_8_FLASH_MODEL,
			jdbcTemplate.queryForObject(
				"select generation_settings ->> 'model' from chat_execution_envelopes where workspace_id = ? and agent_run_id = ?",
				String::class.java,
				devContext.devWorkspaceId,
				agentRunId,
			),
		)
		assertEquals("google-ai-studio", jdbcTemplate.queryForObject(
			"select generation_settings ->> 'routingProvider' from chat_execution_envelopes where workspace_id = ? and agent_run_id = ?",
			String::class.java,
			devContext.devWorkspaceId,
			agentRunId,
		))
		assertEquals("high", jdbcTemplate.queryForObject(
			"select generation_settings ->> 'reasoningEffort' from chat_execution_envelopes where workspace_id = ? and agent_run_id = ?",
			String::class.java,
			devContext.devWorkspaceId,
			agentRunId,
		))

		agentModel.reads = listOf(source.scopeId to blockId)
		assertTrue(agentWorker.processOne())
		assertEquals(PlotAiProperties.GEMINI_3_8_FLASH_MODEL, agentModel.requests.single().model)
		assertEquals("google-ai-studio", agentModel.requests.single().routingProvider)
		assertEquals("high", agentModel.requests.single().reasoningEffort)
	}

	@Test
	fun `Chat Agent discovers two sources without a seed and hands one Artifact to the same Chat`() {
		val first = insertSource("acme/chat-plot")
		val second = insertSource("acme/chat-docs")
		val firstBlock = insertBlock(first, "Chat release", "Release context")
		val secondBlock = insertBlock(second, "Chat docs", "Documentation context")
		val routineCountBefore = count("select count(*) from routine_executions")
		val chat = chatAdmission.admit(
			CreateChatAgentRunRequest("Explore both connected sources"),
			"chat-worker-${UUID.randomUUID()}",
		)
		agentModel.reads = listOf(first.scopeId to firstBlock, second.scopeId to secondBlock)

		repeat(3) { assertTrue(agentWorker.processOne()) }
		val artifactWorkflowRunId = assertNotNull(jdbcTemplate.queryForObject(
			"select id from generation_runs where workspace_id = ? and agent_run_id = ?",
			UUID::class.java,
			devContext.devWorkspaceId,
			chat.id,
		))
		assertEquals(1, count("select count(*) from artifact_runs where workspace_id = ? and agent_run_id = ?", devContext.devWorkspaceId, chat.id))
		assertEquals(chat.chatId, jdbcTemplate.queryForObject(
			"select work_session_id from generation_runs where id = ?",
			UUID::class.java,
			artifactWorkflowRunId,
		))
		assertEquals(2, count("select count(*) from agent_steps where agent_run_id = ? and step_kind = 'READ_TOOL'", chat.id))
		assertEquals(1, count("select count(*) from agent_steps where agent_run_id = ? and step_kind = 'ARTIFACT_HANDOFF'", chat.id))
		assertEquals(2, count("select count(*) from agent_run_inputs where agent_run_id = ? and input_kind = 'TOOL_RESULT'", chat.id))
		assertEquals(routineCountBefore, count("select count(*) from routine_executions"))

		assertEquals(2, artifactWorkflowWorker.drain())
		assertEquals("SUCCEEDED", jdbcTemplate.queryForObject("select status from agent_runs where id = ?", String::class.java, chat.id))
		assertEquals("READY", jdbcTemplate.queryForObject("select status from artifact_runs where workspace_id = ? and agent_run_id = ?", String::class.java, devContext.devWorkspaceId, chat.id))
		val artifactId = assertNotNull(chatQueries.getRun(chat.id).artifactId)
		assertEquals(artifactId, jdbcTemplate.queryForObject(
			"select id from content_packs where generation_run_id = ?",
			UUID::class.java,
			artifactWorkflowRunId,
		))
		assertEquals(artifactWorkflowRunId, jdbcTemplate.queryForObject(
			"select latest_generation_run_id from work_sessions where id = ?",
			UUID::class.java,
			chat.chatId,
		))
		assertEquals(1, count("select count(*) from content_packs where generation_run_id = ?", artifactWorkflowRunId))
		assertEquals(2, count("select count(distinct source_scope_id) from generation_inputs where generation_run_id = ?", artifactWorkflowRunId))
		assertOwnershipAuditClear()

		agentModel.responseText = "I can continue from that artifact."
		val followUp = chatAdmission.admit(
			CreateChatAgentRunRequest(
				instruction = "What did you create?",
				workSessionId = chat.chatId,
			),
			"chat-artifact-follow-up-${UUID.randomUUID()}",
		)
		assertTrue(agentWorker.processOne())
		assertEquals("SUCCEEDED", agentStatus(followUp.id))
		assertTrue(agentModel.requests.last().conversation.any {
			it.role == "assistant" && it.content.contains("A source-backed update is ready.")
		})
	}

	@Test
	fun `Chat Agent answers without a source and carries the conversation into the next turn`() {
		agentModel.responseText = "Plot can answer directly without creating an artifact."
		val first = chatAdmission.admit(
			CreateChatAgentRunRequest("What can you help me with?"),
			"chat-answer-${UUID.randomUUID()}",
		)

		assertTrue(agentWorker.processOne())
		assertEquals("SUCCEEDED", agentStatus(first.id))
		assertEquals("Plot can answer directly without creating an artifact.", chatQueries.getRun(first.id).responseText)
		assertEquals(null, chatQueries.getRun(first.id).artifactId)
		assertEquals(1, count("select count(*) from agent_model_invocations where agent_run_id = ? and status = 'SETTLED'", first.id))
		assertEquals(1, polarCredits.events.count { it.workspaceId == devContext.devWorkspaceId })

		agentModel.responseText = "I remember the earlier answer."
		val followUp = chatAdmission.admit(
			CreateChatAgentRunRequest(
				instruction = "What did you just say?",
				workSessionId = first.chatId,
			),
			"chat-follow-up-${UUID.randomUUID()}",
		)

		assertTrue(agentWorker.processOne())
		assertEquals(
			listOf(
				"user" to "What can you help me with?",
				"assistant" to "Plot can answer directly without creating an artifact.",
			),
			agentModel.requests.last().conversation.map { it.role to it.content },
		)
		val turns = chatQueries.listTurnsForSession(first.chatId)
		assertEquals(2, turns.size)
		assertEquals("I remember the earlier answer.", turns.last().versions.single().responseText)
		assertEquals("SUCCEEDED", agentStatus(followUp.id))
	}

	@Test
	fun `Agent stops before the next physical call after the final credit is consumed`() {
		polarCredits.balance = 1
		var exchanges = 0
		agentModel.nativeRuntime = com.plot.api.ai.provider.KoogAgentRuntime(
			ai.koog.prompt.llm.LLModel(ai.koog.prompt.llm.LLMProvider.OpenRouter, PlotAiProperties.GPT_5_4_NANO_MODEL,
				listOf(ai.koog.prompt.llm.LLMCapability.Completion, ai.koog.prompt.llm.LLMCapability.Tools)),
			ai.koog.prompt.params.LLMParams(), tools.jackson.module.kotlin.jacksonObjectMapper(),
		) { _, _, _ ->
			exchanges++
			com.plot.api.ai.provider.AgentModelResponse(
				nativeCall("LIST_ALLOWED_SOURCES"),
				billedUsage(),
			)
		}
		val admitted = chatAdmission.admit(
			CreateChatAgentRunRequest("Inspect sources before answering"),
			"chat-final-credit-${UUID.randomUUID()}",
		)

		assertTrue(agentWorker.processOne())

		assertEquals(1, exchanges)
		assertEquals("FAILED", agentStatus(admitted.id))
		assertEquals("AI_CREDITS_EXHAUSTED", agentFailure(admitted.id))
		assertEquals(1, count("select count(*) from agent_model_invocations where agent_run_id = ? and status = 'SETTLED'", admitted.id))
	}

	@Test
	fun `pending usage is settled on recovery without repeating provider work`() {
		agentModel.responseText = "The provider completed this once."
		polarCredits.failIngest = true
		val admitted = chatAdmission.admit(
			CreateChatAgentRunRequest("Answer once"),
			"chat-pending-settlement-${UUID.randomUUID()}",
		)

		assertTrue(agentWorker.processOne())
		assertEquals("QUEUED", agentStatus(admitted.id))
		assertEquals(1, agentModel.requests.size)
		assertEquals(1, count("select count(*) from agent_model_invocations where agent_run_id = ? and status = 'PENDING'", admitted.id))

		polarCredits.failIngest = false
		assertTrue(agentWorker.processOne())

		assertEquals(1, agentModel.requests.size)
		assertEquals("FAILED", agentStatus(admitted.id))
		assertEquals("AI_SETTLEMENT_RECOVERED", agentFailure(admitted.id))
		assertEquals(1, count("select count(*) from agent_model_invocations where agent_run_id = ? and status = 'SETTLED'", admitted.id))
		assertEquals(1, polarCredits.events.size)
	}

	@Test
	fun `usage unknown fails one run without locking the workspace`() {
		agentModel.responseText = "Unbillable response"
		agentModel.usage = billedUsage().copy(reportedCostUsd = null)
		val failed = chatAdmission.admit(
			CreateChatAgentRunRequest("First request"),
			"chat-usage-unknown-${UUID.randomUUID()}",
		)

		assertTrue(agentWorker.processOne())
		assertEquals("FAILED", agentStatus(failed.id))
		assertEquals("AI_USAGE_UNKNOWN", agentFailure(failed.id))
		assertEquals(1, count("select count(*) from agent_model_invocations where agent_run_id = ? and status = 'USAGE_UNKNOWN'", failed.id))

		agentModel.usage = billedUsage()
		agentModel.responseText = "Next request succeeds"
		val next = chatAdmission.admit(
			CreateChatAgentRunRequest("Second request"),
			"chat-after-usage-unknown-${UUID.randomUUID()}",
		)
		assertTrue(agentWorker.processOne())
		assertEquals("SUCCEEDED", agentStatus(next.id))
	}

	@Test
	fun `Chat Agent freezes the selected model and routing provider`() {
		agentModel.responseText = "Selected model response."
		val admitted = chatAdmission.admit(
			CreateChatAgentRunRequest(
				instruction = "Use the selected model",
				model = "anthropic/claude-haiku-4.5",
			),
			"chat-model-${UUID.randomUUID()}",
		)

		assertEquals(
			"anthropic/claude-haiku-4.5",
			jdbcTemplate.queryForObject(
				"select generation_settings ->> 'model' from chat_execution_envelopes where workspace_id = ? and agent_run_id = ?",
				String::class.java,
				devContext.devWorkspaceId,
				admitted.id,
			),
		)
		assertEquals(
			"anthropic",
			jdbcTemplate.queryForObject(
				"select generation_settings ->> 'routingProvider' from chat_execution_envelopes where workspace_id = ? and agent_run_id = ?",
				String::class.java,
				devContext.devWorkspaceId,
				admitted.id,
			),
		)
		assertTrue(agentWorker.processOne())
		assertEquals("anthropic/claude-haiku-4.5", agentModel.requests.single().model)
		assertEquals("anthropic", agentModel.requests.single().routingProvider)
	}

	@Test
	fun `automated Artifact admission stays outside Chat`() {
		val run = automationAdmission.admit(
			principal = WorkspacePrincipal(devContext.devWorkspaceId, devContext.devUserId),
			instruction = "Create release notes",
			writingBlockIds = emptyList(),
			idempotencyKey = "automated-chat-${UUID.randomUUID()}",
			title = "Automated release notes",
		)

		assertEquals(com.plot.api.agent.AgentRunOrigin.AUTOMATION, run.origin)
		assertEquals(0, count("select count(*) from chat_response_versions where workspace_id = ? and agent_run_id = ?", devContext.devWorkspaceId, run.id))
		assertEquals("AUTOMATION", jdbcTemplate.queryForObject(
			"select session_kind from work_sessions where workspace_id = ? and id = ?",
			String::class.java,
			devContext.devWorkspaceId,
			run.workSessionId,
		))
		assertFalse(workSessions.list().any { it.id == run.workSessionId })
		assertFailsWith<ApiException> { chatQueries.listForSession(requireNotNull(run.workSessionId)) }
		assertEquals(
			AgentResponseMode.ARTIFACT_REQUIRED.name,
			jdbcTemplate.queryForObject(
				"select generation_settings ->> 'responseMode' from chat_execution_envelopes where workspace_id = ? and agent_run_id = ?",
				String::class.java,
				devContext.devWorkspaceId,
				run.id,
			),
		)
	}

	@Test
	fun `model cannot read an unconfigured source`() {
		val trigger = insertSource("acme/secure")
		val blockId = insertBlock(trigger, "Allowed", "Allowed body")
		val routine = routinePersistence.insert(
			devContext.devWorkspaceId,
			devContext.devUserId,
			"Allowlist routine",
			trigger.scopeId,
			"Inspect only configured sources",
			RoutineCadence.DAILY,
		)
		val execution = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = routine.workspaceId,
				routineId = routine.id,
				createdByUserId = routine.createdByUserId,
				triggerSourceScopeId = routine.sourceScopeId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "manual:${routine.id}:reject",
				requestFingerprint = "reject:${routine.id}",
				activityCursorBefore = routine.activityCursorSequence,
			),
		)
		routineWorker.runNow(routine.workspaceId, routine.id, execution.id)
		val agentRunId = jdbcTemplate.queryForObject(
			"select id from agent_runs where routine_execution_id = ?",
			UUID::class.java,
			execution.id,
		)!!
		agentModel.invalidSourceId = UUID.randomUUID()

		assertTrue(agentWorker.processOne())

		assertEquals("FAILED", jdbcTemplate.queryForObject("select status from agent_runs where id = ?", String::class.java, agentRunId))
		assertEquals("AGENT_INVALID_DECISION", jdbcTemplate.queryForObject("select failure_code from agent_runs where id = ?", String::class.java, agentRunId))
		assertEquals(0, count("select count(*) from agent_steps where agent_run_id = ?", agentRunId))
		assertEquals(null, routinePersistence.find(routine.workspaceId, routine.id)?.activityCursorSequence)
		assertEquals("FAILED", jdbcTemplate.queryForObject("select status from routine_executions where id = ?", String::class.java, execution.id))
		routinePersistence.find(routine.workspaceId, routine.id)!!.also { projected ->
			assertEquals("FAILED", projected.lastRunStatus)
			assertEquals("AGENT_INVALID_DECISION", projected.lastErrorCode)
			assertEquals(null, projected.activeExecutionId)
		}

		val retry = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = routine.workspaceId,
				routineId = routine.id,
				createdByUserId = routine.createdByUserId,
				triggerSourceScopeId = routine.sourceScopeId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "manual:${routine.id}:retry-failed-seed",
				requestFingerprint = "retry-failed-seed:${routine.id}",
				activityCursorBefore = null,
			),
		)
		routineWorker.runNow(routine.workspaceId, routine.id, retry.id)

		assertEquals(2, count(
			"select count(*) from agent_run_inputs where routine_id = ? and writing_block_id = ? and input_kind = 'SEED'",
			routine.id,
			blockId,
		))
	}

	@Test
	fun `tool budget stops the model after exactly two read-only calls`() {
		val admitted = admitAgent("Budget routine", "acme/budget")
		agentModel.repeatAction = AgentDecisionAction.LIST_ALLOWED_SOURCES

		assertTrue(agentWorker.processOne())
		assertTrue(agentWorker.processOne())
		assertTrue(agentWorker.processOne())

		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
		assertEquals("AGENT_TOOL_CALL_LIMIT", jdbcTemplate.queryForObject(
			"select failure_code from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
		assertEquals(3, agentModel.requests.size)
		assertEquals(2, count("select count(*) from agent_steps where agent_run_id = ?", admitted.agentRunId))
		assertEquals(2, count("select tool_call_count from agent_runs where id = ?", admitted.agentRunId))
	}

	@Test
	fun `recoverable model failures stop at the frozen attempt limit`() {
		val admitted = admitAgent("Retry routine", "acme/retry")
		agentModel.recoverableFailure = true

		assertTrue(agentWorker.processOne())
		assertTrue(agentWorker.processOne())
		assertFalse(agentWorker.processOne())

		assertEquals(2, agentModel.requests.size)
		assertEquals(2, count("select attempt_count from agent_runs where id = ?", admitted.agentRunId))
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
		assertEquals("PROVIDER_UNAVAILABLE", jdbcTemplate.queryForObject(
			"select failure_code from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
	}

	@Test
	fun `unknown failures retain a recoverable claim but cannot retry forever`() {
		val admitted = admitAgent("Infrastructure routine", "acme/infrastructure")
		agentModel.infrastructureFailure = true

		repeat(2) {
			assertFailsWith<IllegalStateException> { agentWorker.processOne() }
			jdbcTemplate.update(
				"update agent_runs set claimed_at = now() - interval '2 seconds' where id = ?",
				admitted.agentRunId,
			)
		}
		assertFalse(agentWorker.processOne())

		assertEquals(2, agentModel.requests.size)
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
		assertEquals("AGENT_RETRY_EXHAUSTED", jdbcTemplate.queryForObject(
			"select failure_code from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
	}

	@Test
	fun `stale read recovery adopts one immutable result without another model call`() {
		val admitted = admitAgent("Recovery routine", "acme/recovery")
		val now = Instant.now()
		val crashedClaim = assertNotNull(agentRunExecutionPersistence.claimNextAgentRun(
			workerId = "crashed-agent",
			now = now,
			staleBefore = now.minusSeconds(2),
		))
		agentRunExecutionPersistence.beginModelDecision(crashedClaim, 3)
		agentRunExecutionPersistence.reserveStep(
			claim = crashedClaim,
			request = AgentStepRequest(
				agentRunId = admitted.agentRunId,
				sequence = 0,
				kind = AgentStepKind.READ_TOOL,
				status = AgentStepStatus.RUNNING,
				idempotencyKey = "agent:${admitted.agentRunId}:step:0",
				toolName = AgentDecisionAction.READ_WRITING_BLOCKS.name,
				argumentsJson = """{"action":"READ_WRITING_BLOCKS","sourceScopeId":"${admitted.source.scopeId}","query":null,"writingBlockId":"${admitted.blockId}","selectedInputIds":[]}""",
			),
			maxToolCalls = 2,
			now = now,
		)
		jdbcTemplate.update(
			"update agent_runs set claimed_at = now() - interval '2 seconds' where id = ?",
			admitted.agentRunId,
		)

		assertTrue(agentWorker.processOne())

		assertEquals(0, agentModel.requests.size)
		assertEquals(1, count("select count(*) from agent_steps where agent_run_id = ?", admitted.agentRunId))
		assertEquals(1, count(
			"select count(*) from agent_run_inputs where agent_run_id = ? and input_kind = 'TOOL_RESULT'",
			admitted.agentRunId,
		))
		assertEquals(1, count("select tool_call_count from agent_runs where id = ?", admitted.agentRunId))
	}

	@Test
	fun `invalid decision is rejected with feedback and the agent recovers`() {
		val admitted = admitAgent("Feedback routine", "acme/feedback")
		agentModel.scriptedDecision = { request ->
			if (request.completedSteps.isEmpty()) {
				AgentDecision(
					AgentDecisionAction.READ_WRITING_BLOCKS,
					sourceScopeId = admitted.source.scopeId,
					writingBlockIds = listOf(admitted.blockId, admitted.blockId),
				)
			} else {
				AgentDecision(
					AgentDecisionAction.CREATE_ARTIFACT,
					selectedInputIds = request.inputs.map { it.id },
				)
			}
		}

		assertTrue(agentWorker.processOne())

		assertEquals("RUNNING", jdbcTemplate.queryForObject(
			"select status from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
		assertEquals("AGENT_INVALID_DECISION", jdbcTemplate.queryForObject(
			"select failure_code from agent_steps where agent_run_id = ? and sequence = 0",
			String::class.java,
			admitted.agentRunId,
		))
		assertTrue(jdbcTemplate.queryForObject(
			"select result::text from agent_steps where agent_run_id = ? and sequence = 0",
			String::class.java,
			admitted.agentRunId,
		)!!.contains("Read requires exactly one source item"))

		assertTrue(agentWorker.processOne())
		assertEquals(2, agentModel.requests.size)
		assertTrue(agentModel.requests[1].completedSteps.any {
			it.result?.contains("Read requires exactly one source item") == true
		})
		assertEquals(1, count(
			"select count(*) from agent_steps where agent_run_id = ? and step_kind = 'ARTIFACT_HANDOFF'",
			admitted.agentRunId,
		))
	}

	@Test
	fun `invalid artifact selection is rejected with the unknown input IDs`() {
		val admitted = admitAgent("Selection feedback routine", "acme/selection-feedback")
		val unknownId = UUID.randomUUID()
		agentModel.scriptedDecision = { request ->
			if (request.completedSteps.isEmpty()) {
				AgentDecision(
					AgentDecisionAction.CREATE_ARTIFACT,
					selectedInputIds = request.inputs.map { it.id } + unknownId,
				)
			} else {
				AgentDecision(
					AgentDecisionAction.CREATE_ARTIFACT,
					selectedInputIds = request.inputs.map { it.id },
				)
			}
		}

		assertTrue(agentWorker.processOne())

		assertEquals("RUNNING", jdbcTemplate.queryForObject(
			"select status from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
		val rejection = jdbcTemplate.queryForObject(
			"select result::text from agent_steps where agent_run_id = ? and sequence = 0",
			String::class.java,
			admitted.agentRunId,
		)!!
		assertTrue(rejection.contains(unknownId.toString()))
		assertTrue(rejection.contains("inputs list"))

		assertTrue(agentWorker.processOne())
		assertTrue(agentModel.requests[1].completedSteps.any {
			it.result?.contains(unknownId.toString()) == true
		})
		assertEquals(1, count(
			"select count(*) from agent_steps where agent_run_id = ? and step_kind = 'ARTIFACT_HANDOFF' and status = 'SUCCEEDED'",
			admitted.agentRunId,
		))
	}

	@Test
	fun `read with an Agent input id is remapped to its writing block`() {
		val admitted = admitAgent("Remap routine", "acme/remap")
		val seedInputId = assertNotNull(jdbcTemplate.queryForObject(
			"select id from agent_run_inputs where agent_run_id = ? and input_kind = 'SEED'",
			UUID::class.java,
			admitted.agentRunId,
		))
		agentModel.scriptedDecision = { request ->
			if (request.completedSteps.none { it.toolName == AgentDecisionAction.READ_WRITING_BLOCKS.name }) {
				AgentDecision(
					AgentDecisionAction.READ_WRITING_BLOCKS,
					sourceScopeId = admitted.source.scopeId,
					writingBlockIds = listOf(seedInputId),
				)
			} else {
				AgentDecision(
					AgentDecisionAction.CREATE_ARTIFACT,
					selectedInputIds = request.inputs.map { it.id },
				)
			}
		}

		assertTrue(agentWorker.processOne())
		assertTrue(agentWorker.processOne())

		assertEquals(1, count(
			"select count(*) from agent_steps where agent_run_id = ? and step_kind = 'READ_TOOL' and status = 'SUCCEEDED'",
			admitted.agentRunId,
		))
		assertEquals(admitted.blockId, jdbcTemplate.queryForObject(
			"select writing_block_id from agent_run_inputs where agent_run_id = ? and input_kind = 'TOOL_RESULT'",
			UUID::class.java,
			admitted.agentRunId,
		))
		assertEquals(1, count(
			"select count(*) from agent_steps where agent_run_id = ? and step_kind = 'ARTIFACT_HANDOFF'",
			admitted.agentRunId,
		))
	}

	@Test
	fun `source disconnect discards a model decision before Artifact handoff`() {
		val admitted = admitAgent("Disconnect routine", "acme/disconnect")
		agentModel.scriptedDecision = { request ->
			jdbcTemplate.update(
				"update source_scopes set status = 'DISABLED', status_changed_at = now(), updated_at = now() where id = ?",
				admitted.source.scopeId,
			)
			AgentDecision(
				AgentDecisionAction.CREATE_ARTIFACT,
				selectedInputIds = request.inputs.map { it.id },
			)
		}

		assertTrue(agentWorker.processOne())

		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
		assertEquals("SOURCE_NOT_READY", jdbcTemplate.queryForObject(
			"select failure_code from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
		assertEquals(0, count("select count(*) from agent_steps where agent_run_id = ?", admitted.agentRunId))
		assertEquals(0, count("select count(*) from generation_runs where agent_run_id = ?", admitted.agentRunId))
	}

	@Test
	fun `source disconnect after handoff does not invalidate immutable Artifact evidence`() {
		val admitted = admitAgent("Post-handoff disconnect", "acme/post-handoff")
		agentModel.scriptedDecision = { request ->
			AgentDecision(
				AgentDecisionAction.CREATE_ARTIFACT,
				selectedInputIds = request.inputs.map { it.id },
			)
		}

		assertTrue(agentWorker.processOne())
		jdbcTemplate.update(
			"update source_scopes set status = 'DISABLED', status_changed_at = now(), updated_at = now() where id = ?",
			admitted.source.scopeId,
		)
		assertEquals(2, artifactWorkflowWorker.drain())

		assertEquals("SUCCEEDED", jdbcTemplate.queryForObject(
			"select status from agent_runs where id = ?",
			String::class.java,
			admitted.agentRunId,
		))
		assertEquals(1, count(
			"select count(*) from content_packs where generation_run_id = (select id from generation_runs where agent_run_id = ?)",
			admitted.agentRunId,
		))
	}

	@Test
	fun `workspace revocation before an Agent decision prevents model and tool work`() {
		val admitted = admitAgent("Revoked before model", "acme/revoked-model")
		setWorkspaceAccess("revoked", "read_only")

		assertTrue(agentWorker.processOne())

		assertEquals(0, agentModel.requests.size)
		assertEquals("FAILED", agentStatus(admitted.agentRunId))
		assertEquals("WORKSPACE_READ_ONLY", agentFailure(admitted.agentRunId))
		assertEquals(0, count("select count(*) from agent_steps where agent_run_id = ?", admitted.agentRunId))
		assertEquals(0, count("select count(*) from generation_runs where agent_run_id = ?", admitted.agentRunId))
	}

	@Test
	fun `workspace revocation after an Agent decision prevents the typed read`() {
		val admitted = admitAgent("Revoked before read", "acme/revoked-read")
		agentModel.scriptedDecision = {
			setWorkspaceAccess("revoked", "read_only")
			AgentDecision(
				AgentDecisionAction.READ_WRITING_BLOCKS,
				sourceScopeId = admitted.source.scopeId,
				writingBlockIds = listOf(admitted.blockId),
			)
		}

		assertTrue(agentWorker.processOne())

		assertEquals(1, agentModel.requests.size)
		assertEquals("FAILED", agentStatus(admitted.agentRunId))
		assertEquals("WORKSPACE_READ_ONLY", agentFailure(admitted.agentRunId))
		assertEquals(0, count(
			"select count(*) from agent_run_inputs where agent_run_id = ? and input_kind = 'TOOL_RESULT'",
			admitted.agentRunId,
		))
		assertEquals(0, count("select count(*) from generation_runs where agent_run_id = ?", admitted.agentRunId))
	}

	@Test
	fun `workspace revocation after an Agent decision prevents ArtifactWorkflow handoff`() {
		val admitted = admitAgent("Revoked before handoff", "acme/revoked-handoff")
		agentModel.scriptedDecision = { request ->
			setWorkspaceAccess("revoked", "read_only")
			AgentDecision(
				AgentDecisionAction.CREATE_ARTIFACT,
				selectedInputIds = request.inputs.map { it.id },
			)
		}

		assertTrue(agentWorker.processOne())

		assertEquals(1, agentModel.requests.size)
		assertEquals("FAILED", agentStatus(admitted.agentRunId))
		assertEquals("WORKSPACE_READ_ONLY", agentFailure(admitted.agentRunId))
		assertEquals(0, count("select count(*) from generation_runs where agent_run_id = ?", admitted.agentRunId))
	}

	@Test
	fun `workspace revocation after handoff prevents the ArtifactWorkflow model call`() {
		val admitted = admitAgent("Revoked before generation", "acme/revoked-generation")
		agentModel.scriptedDecision = { request ->
			AgentDecision(
				AgentDecisionAction.CREATE_ARTIFACT,
				selectedInputIds = request.inputs.map { it.id },
			)
		}
		assertTrue(agentWorker.processOne())
		val artifactWorkflowRunId = assertNotNull(jdbcTemplate.queryForObject(
			"select id from generation_runs where agent_run_id = ?",
			UUID::class.java,
			admitted.agentRunId,
		))
		setWorkspaceAccess("revoked", "read_only")

		assertTrue(artifactWorkflowWorker.processOne())

		assertEquals(0, artifactWorkflowModel.calls)
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from generation_runs where id = ?",
			String::class.java,
			artifactWorkflowRunId,
		))
		assertEquals("WORKSPACE_READ_ONLY", jdbcTemplate.queryForObject(
			"select error_code from generation_runs where id = ?",
			String::class.java,
			artifactWorkflowRunId,
		))
		assertEquals(0, count("select count(*) from content_packs where generation_run_id = ?", artifactWorkflowRunId))
		assertEquals("FAILED", agentStatus(admitted.agentRunId))
		assertEquals("AGENT_ARTIFACT_WORKFLOW_FAILED", agentFailure(admitted.agentRunId))
		assertOwnershipAuditClear()
	}

	@Test
	fun `search returns bounded source results before Artifact handoff`() {
		val admitted = admitAgent("Search routine", "acme/search")
		agentModel.scriptedDecision = { request ->
			if (request.completedSteps.isEmpty()) {
				AgentDecision(
					AgentDecisionAction.SEARCH_WRITING_BLOCKS,
					sourceScopeId = admitted.source.scopeId,
					query = "immutable",
				)
			} else {
				AgentDecision(
					AgentDecisionAction.CREATE_ARTIFACT,
					selectedInputIds = request.inputs.map { it.id },
				)
			}
		}

		assertTrue(agentWorker.processOne())
		assertTrue(agentWorker.processOne())

		val result = assertNotNull(jdbcTemplate.queryForObject(
			"select result::text from agent_steps where agent_run_id = ? and tool_name = 'SEARCH_WRITING_BLOCKS'",
			String::class.java,
			admitted.agentRunId,
		))
		assertTrue(result.contains(admitted.blockId.toString()))
		assertTrue(result.length < 2_000)
		assertEquals(1, count("select count(*) from generation_runs where agent_run_id = ?", admitted.agentRunId))
	}

	@Test
	fun `chat retry replays frozen search after the live connection disconnects`() {
		val source = insertSource("acme/frozen-replay")
		val blockId = insertBlock(source, "Frozen search", "Immutable original search evidence")
		val admitted = chatAdmission.admit(
			CreateChatAgentRunRequest(
				instruction = "Draft from frozen evidence",
				writingBlockIds = listOf(blockId),
			),
			"frozen-replay-original-${UUID.randomUUID()}",
		)
		var retrying = false
		agentModel.scriptedDecision = { request ->
			if (request.completedSteps.isEmpty()) {
				AgentDecision(
					AgentDecisionAction.SEARCH_WRITING_BLOCKS,
					sourceScopeId = source.scopeId,
					query = "immutable",
				)
			} else if (!retrying) {
				throw AgentDecisionException("ORIGINAL_TERMINAL", false, "End the original run")
			} else {
				AgentDecision(
					AgentDecisionAction.CREATE_ARTIFACT,
					selectedInputIds = request.inputs.map { it.id },
				)
			}
		}

		assertTrue(agentWorker.processOne())
		assertTrue(agentWorker.processOne())
		assertEquals("FAILED", agentStatus(admitted.id))

		val targetVersionId = chatQueries.listTurnsForSession(admitted.chatId).single().versions.single().id
		val retried = chatAdmission.retry(targetVersionId, "frozen-replay-retry-${UUID.randomUUID()}")
		retrying = true
		assertTrue(jdbcTemplate.queryForObject(
			"select exists(select 1 from chat_response_versions where agent_run_id = ? and lineage_parent_version_id is not null)",
			Boolean::class.java,
			retried.agentRunId,
		) == true)
		jdbcTemplate.update(
			"update connections set status = 'DISABLED', status_changed_at = now(), updated_at = now() where workspace_id = ?",
			devContext.devWorkspaceId,
		)

		assertTrue(agentWorker.processOne())
		assertEquals(3, agentModel.requests.size)
		assertEquals("RUNNING", agentStatus(retried.agentRunId))
		val replayResult = assertNotNull(jdbcTemplate.queryForObject(
			"select result::text from agent_steps where agent_run_id = ? and tool_name = 'SEARCH_WRITING_BLOCKS'",
			String::class.java,
			retried.agentRunId,
		))
		assertTrue(replayResult.contains(blockId.toString()))
	}

	@Test
	fun `native Koog runtime loads deleted supporting skill and creates a grounded artifact in one claim`() {
		val skillId = UUID.randomUUID()
		jdbcTemplate.update("insert into skills (id, workspace_id, name, description, content) values (?, ?, ?, ?, ?)",
			skillId, devContext.devWorkspaceId, "native-${UUID.randomUUID()}", "Supporting guide", "Frozen supporting instruction.")
		val admitted = admitAgent("Native runtime", "acme/native")
		jdbcTemplate.update("update agent_runs set budget_snapshot = budget_snapshot || '{\"maxModelCalls\":8,\"maxToolCalls\":8}'::jsonb where id = ?", admitted.agentRunId)
		jdbcTemplate.update("delete from skills where id = ?", skillId)
		var modelCalls = 0
		agentModel.nativeRuntime = nativeRuntime { prompt ->
			modelCalls++
			when (modelCalls) {
				1 -> { assertFalse(prompt.contains("Frozen supporting instruction.")); nativeCall("LIST_AVAILABLE_SKILLS") }
				2 -> { assertFalse(prompt.contains("Frozen supporting instruction.")); assertTrue(prompt.contains(skillId.toString()))
					nativeCall("GET_SKILL", """{"skillId":"$skillId"}""") }
				3 -> { assertTrue(prompt.contains("Frozen supporting instruction."))
					nativeCall("READ_WRITING_BLOCKS", """{"sourceScopeId":"${admitted.source.scopeId}","writingBlockIds":["${admitted.blockId}"]}""") }
				else -> {
					val inputId = jdbcTemplate.queryForObject("select id from agent_run_inputs where agent_run_id = ? and input_kind = 'TOOL_RESULT'", UUID::class.java, admitted.agentRunId)!!
					assertTrue(prompt.contains(inputId.toString()))
					nativeCall("CREATE_ARTIFACT", """{"selectedInputIds":["$inputId"]}""")
				}
			}
		}
		assertTrue(agentWorker.processOne())
		assertEquals(4, modelCalls)
		assertEquals(4, count("select model_call_count from agent_runs where id = ?", admitted.agentRunId))
		assertEquals(3, count("select tool_call_count from agent_runs where id = ?", admitted.agentRunId))
		assertEquals(1, count("select count(*) from generation_runs where agent_run_id = ?", admitted.agentRunId))
		artifactWorkflowWorker.drain()
		assertEquals("SUCCEEDED", agentStatus(admitted.agentRunId))
		assertTrue(artifactWorkflowModel.writerRequests.single().instruction.orEmpty().contains("Frozen supporting instruction."))
	}

	@Test
	fun `native Koog runtime refuses a skill outside the frozen catalog`() {
		val admitted = admitAgent("Forbidden skill", "acme/forbidden-skill")
		agentModel.nativeRuntime = nativeRuntime { nativeCall("GET_SKILL", """{"skillId":"${UUID.randomUUID()}"}""") }
		assertTrue(agentWorker.processOne())
		assertEquals("FAILED", agentStatus(admitted.agentRunId))
		assertEquals("SKILL_NOT_ALLOWED", agentFailure(admitted.agentRunId))
		assertEquals(0, count("select count(*) from generation_runs where agent_run_id = ?", admitted.agentRunId))
	}

	private fun nativeRuntime(next: (String) -> ai.koog.prompt.message.Message.Assistant): AgentRuntime =
		com.plot.api.ai.provider.KoogAgentRuntime(
			ai.koog.prompt.llm.LLModel(ai.koog.prompt.llm.LLMProvider.OpenRouter, "test", listOf(ai.koog.prompt.llm.LLMCapability.Completion, ai.koog.prompt.llm.LLMCapability.Tools)),
			ai.koog.prompt.params.LLMParams(), tools.jackson.module.kotlin.jacksonObjectMapper(),
		) { prompt, _, _ -> com.plot.api.ai.provider.AgentModelResponse(
			next(prompt.toString()),
			billedUsage(),
		) }

	private fun nativeCall(name: String, arguments: String = "{}") = ai.koog.prompt.message.Message.Assistant(
		parts = listOf(ai.koog.prompt.message.MessagePart.Tool.Call(UUID.randomUUID().toString(), name,
			kotlinx.serialization.json.Json.parseToJsonElement(arguments) as kotlinx.serialization.json.JsonObject)),
		metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty,
	)

	private fun admitAgent(name: String, sourceLabel: String): AdmittedAgent {
		val source = insertSource(sourceLabel)
		val blockId = insertBlock(source, "Activity", "Immutable activity body")
		val routine = routinePersistence.insert(
			devContext.devWorkspaceId,
			devContext.devUserId,
			name,
			source.scopeId,
			"Create a source-backed Artifact",
			RoutineCadence.DAILY,
		)
		val execution = agentPersistence.createExecution(
			RoutineExecutionRequest(
				workspaceId = routine.workspaceId,
				routineId = routine.id,
				createdByUserId = routine.createdByUserId,
				triggerSourceScopeId = source.scopeId,
				triggerKind = RoutineExecutionTriggerKind.MANUAL,
				triggerKey = "manual:${routine.id}:${UUID.randomUUID()}",
				requestFingerprint = "agent:${routine.id}",
				activityCursorBefore = routine.activityCursorSequence,
			),
		)
		routineWorker.runNow(routine.workspaceId, routine.id, execution.id)
		val agentRunId = assertNotNull(jdbcTemplate.queryForObject(
			"select id from agent_runs where routine_execution_id = ?",
			UUID::class.java,
			execution.id,
		))
		return AdmittedAgent(source, blockId, agentRunId)
	}

	private fun insertSource(label: String): AgentSourceFixture {
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
			values (?, ?, 'GITHUB', 'INSTALLATION', ?, ?, 'ACTIVE', now(), now())
			""".trimIndent(),
			namespaceId,
			devContext.devWorkspaceId,
			"installation-${UUID.randomUUID()}",
			label,
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
			 external_scope_key, external_key, display_name, status, status_changed_at, created_at, updated_at)
			values (?, ?, ?, 'GITHUB', 'CONTAINER', 'REPOSITORY', ?, ?, ?, 'ACTIVE', now(), now(), now())
			""".trimIndent(),
			scopeId,
			devContext.devWorkspaceId,
			namespaceId,
			"repository-${UUID.randomUUID()}",
			label,
			label,
		)
		return AgentSourceFixture(namespaceId, scopeId)
	}

	private fun insertBlock(source: AgentSourceFixture, title: String, body: String): UUID {
		val id = UUID.randomUUID()
		val now = Instant.parse("2026-08-09T00:00:00Z")
		val url = "https://github.com/acme/plot/commit/$id"
		jdbcTemplate.update(
			"""
			insert into writing_blocks (
			 id, workspace_id, source_namespace_id, external_object_key, source_origin, source_kind,
			 title, body, url, canonical_url, platform, content_hash, source_created_at, source_updated_at,
			 ingested_at, status, created_at, updated_at
			) values (?, ?, ?, ?, 'integration', 'commit', ?, ?, ?, ?, 'github', ?, ?, ?, ?, 'ACTIVE', ?, ?)
			""".trimIndent(),
			id,
			devContext.devWorkspaceId,
			source.namespaceId,
			"commit:$id",
			title,
			body,
			url,
			url,
			"hash-$id",
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
		)
		jdbcTemplate.update(
			"""
			insert into writing_block_scopes (
			 id, workspace_id, source_namespace_id, writing_block_id, source_scope_id,
			 membership_kind, status, first_seen_at, last_seen_at
			) values (?, ?, ?, ?, ?, 'CONTAINED_IN', 'ACTIVE', ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			devContext.devWorkspaceId,
			source.namespaceId,
			id,
			source.scopeId,
			Timestamp.from(now),
			Timestamp.from(now),
		)
		return id
	}

	private fun count(sql: String, vararg args: Any): Int =
		jdbcTemplate.queryForObject(sql, Int::class.java, *args) ?: 0
	private fun assertOwnershipAuditClear() {
		val sql = ClassPathResource("db/audit/v1/agent_artifact_ownership.sql")
			.inputStream
			.bufferedReader()
			.use { it.readText() }
		assertTrue(jdbcTemplate.queryForList(sql).isEmpty())
	}

	private fun setWorkspaceAccess(status: String, accessMode: String) {
		jdbcTemplate.update(
			"update workspaces set plan = 'founding', entitlement_status = ?, access_mode = ?, updated_at = now() where id = ?",
			status,
			accessMode,
			devContext.devWorkspaceId,
		)
	}

	private fun agentStatus(agentRunId: UUID): String = assertNotNull(jdbcTemplate.queryForObject(
		"select status from agent_runs where id = ?",
		String::class.java,
		agentRunId,
	))

	private fun agentFailure(agentRunId: UUID): String = assertNotNull(jdbcTemplate.queryForObject(
		"select failure_code from agent_runs where id = ?",
		String::class.java,
		agentRunId,
	))

	@TestConfiguration(proxyBeanMethods = false)
	class Config {
		@Bean
		@Primary
		fun scriptedAgentRuntime() = ScriptedAgentRuntime()

		@Bean
		@Primary
		fun scriptedAgentArtifactWorkflowGateway() = AgentArtifactWorkflowModelGateway()

		@Bean
		@Primary
		fun polarCreditProvider() = AgentPolarCreditProvider()

		@Bean
		@Primary
		fun noOpArtifactWorkflowDispatcher(): ArtifactWorkflowRunDispatcher =
			ArtifactWorkflowRunDispatcher(TaskExecutor { _ -> }) { false }
	}
}

private data class AgentSourceFixture(val namespaceId: UUID, val scopeId: UUID)
private data class AdmittedAgent(val source: AgentSourceFixture, val blockId: UUID, val agentRunId: UUID)

class ScriptedAgentRuntime : AgentRuntime {
	var nativeRuntime: AgentRuntime? = null
	var reads: List<Pair<UUID, UUID>> = emptyList()
	var invalidSourceId: UUID? = null
	var repeatAction: AgentDecisionAction? = null
	var recoverableFailure = false
	var infrastructureFailure = false
	var scriptedDecision: ((AgentDecisionRequest) -> AgentDecision)? = null
	var responseText: String? = null
	var usage: ProviderUsage = billedUsage()
	val requests = mutableListOf<AgentDecisionRequest>()

	override fun run(host: AgentRuntimeHost): com.plot.api.ai.provider.AgentRuntimeResult {
		nativeRuntime?.let { return it.run(host) }
		host.beforeModel()
		return try {
			responseText?.let {
				requests += host.context()
				host.afterModel(usage)
				return com.plot.api.ai.provider.AgentRuntimeResult(responseText = it)
			}
			val decision = decide(host.context())
			host.afterModel(usage)
			host.execute(decision)
			com.plot.api.ai.provider.AgentRuntimeResult(completed = host.finished)
		} catch (failure: AgentDecisionException) {
			host.modelFailed(failure)
			throw failure
		} catch (failure: RuntimeException) {
			host.modelFailed(AgentDecisionException("PROVIDER_UNAVAILABLE", true, "Scripted provider failed"))
			throw failure
		}
	}

	private fun decide(request: AgentDecisionRequest): AgentDecision {
		requests += request
		if (recoverableFailure) {
			throw AgentDecisionException("PROVIDER_UNAVAILABLE", true, "Temporary provider failure")
		}
		if (infrastructureFailure) error("Unexpected infrastructure failure")
		scriptedDecision?.let { return it(request) }
		repeatAction?.let { return AgentDecision(it) }
		invalidSourceId?.let { sourceId ->
			return AgentDecision(
				AgentDecisionAction.READ_WRITING_BLOCKS,
				sourceScopeId = sourceId,
				writingBlockIds = listOf(UUID.randomUUID()),
			)
		}
		val readIndex = request.completedSteps.count { it.toolName == AgentDecisionAction.READ_WRITING_BLOCKS.name }
		if (readIndex < reads.size) {
			val (sourceScopeId, writingBlockId) = reads[readIndex]
			return AgentDecision(
				AgentDecisionAction.READ_WRITING_BLOCKS,
				sourceScopeId = sourceScopeId,
				writingBlockIds = listOf(writingBlockId),
			)
		}
		return AgentDecision(
			AgentDecisionAction.CREATE_ARTIFACT,
			selectedInputIds = request.inputs
				.filter { input -> request.completedSteps.any { it.result?.contains(input.id.toString()) == true } }
				.map { it.id }
				.ifEmpty { request.inputs.takeLast(reads.size).map { it.id } },
		)
	}

	fun reset() {
		nativeRuntime = null
		reads = emptyList()
		invalidSourceId = null
		repeatAction = null
		recoverableFailure = false
		infrastructureFailure = false
		scriptedDecision = null
		responseText = null
		usage = billedUsage()
		requests.clear()
	}
}

private fun billedUsage() = ProviderUsage(
	provider = "openrouter",
	requestedModel = PlotAiProperties.GPT_5_4_NANO_MODEL,
	actualModel = PlotAiProperties.GPT_5_4_NANO_MODEL,
	responseId = UUID.randomUUID().toString(),
	inputTokens = 1,
	outputTokens = 1,
	cacheReadTokens = 0,
	cacheWriteTokens = 0,
	reasoningTokens = 0,
	totalTokens = 2,
	reportedCostUsd = java.math.BigDecimal("0.0000005"),
)

class AgentPolarCreditProvider : PolarCreditProvider {
	var balance = 10_000L
	var failIngest = false
	var ingestAttempts = 0
	val events = mutableListOf<AgentPolarEvent>()

	override fun ensureCustomer(workspaceId: UUID, ownerEmail: String, workspaceName: String) =
		PolarCustomer("cus-$workspaceId", "plot-workspace:$workspaceId")

	override fun readCreditBalance(workspaceId: UUID): Long = balance

	override fun grantTrialCredits(workspaceId: UUID) = PolarEventResult(0, 1)

	override fun ingestCredits(workspaceId: UUID, eventId: String, credits: Long, metadata: Map<String, Any>): PolarEventResult {
		ingestAttempts++
		if (failIngest) throw com.plot.api.billing.PolarApiException("POLAR_UNAVAILABLE", "Polar unavailable", retryable = true)
		if (events.none { it.eventId == eventId }) {
			events += AgentPolarEvent(workspaceId, eventId, credits)
			balance -= credits
			return PolarEventResult(1, 0)
		}
		return PolarEventResult(0, 1)
	}

	fun reset() {
		balance = 10_000
		failIngest = false
		ingestAttempts = 0
		events.clear()
	}
}

data class AgentPolarEvent(val workspaceId: UUID, val eventId: String, val credits: Long)

class AgentArtifactWorkflowModelGateway : ArtifactWorkflowModelGateway {
	var calls = 0
	val writerRequests = mutableListOf<WriterModelRequest>()

	override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> {
		writerRequests += request
		calls++
		return result(WriterOutput(listOf(WriterSentence("A source-backed update is ready."))))
	}

	override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> {
		calls++
		return result(ReviewerOutput(listOf(SentenceReview(
			request.sentences.single().id,
			ReviewVerdict.SUPPORTED,
			request.evidence.map { it.id },
		))))
	}

	override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> =
		error("Agent artifact workflow should not require a rewrite")

	fun reset() {
		writerRequests.clear()
		calls = 0
	}

	private fun <T : Any> result(value: T) = ModelCallResult(
		value,
		ModelCallMetadata(
			responseId = "agent-e2e",
			actualModel = "scripted",
			finishReason = "stop",
			promptTokens = 1,
			completionTokens = 1,
			totalTokens = 2,
			latency = Duration.ofMillis(1),
			observationAttributes = emptyMap(),
			gateway = "openrouter",
			requestedModel = "scripted",
			cacheReadTokens = 0,
			cacheWriteTokens = 0,
			reasoningTokens = 0,
			reportedCostUsd = java.math.BigDecimal("0.000001"),
		),
	)
}
