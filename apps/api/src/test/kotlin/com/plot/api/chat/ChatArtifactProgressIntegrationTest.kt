package com.plot.api.chat

import com.plot.api.TestcontainersConfiguration
import com.plot.api.agent.*
import com.plot.api.ai.provider.*
import com.plot.api.artifact.workflow.*
import com.plot.api.artifact.workflow.model.*
import com.plot.api.chat.dto.CreateChatAgentRunRequest
import com.plot.api.dev.DevBootstrapService
import com.plot.api.dev.DevContext
import com.plot.api.routine.AgentRunWorkerIntegrationTest
import com.plot.api.routine.ScriptedAgentRuntime
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

@SpringBootTest
@Import(TestcontainersConfiguration::class, AgentRunWorkerIntegrationTest.Config::class)
@ActiveProfiles("test")
@TestPropertySource(properties = ["server.address=127.0.0.1", "plot.dev-bootstrap.enabled=true", "plot.routine-agent.workers-enabled=true", "plot.routine-agent.auto-dispatch-enabled=false", "plot.polar.credits-enabled=\${PLOT_EVAL_LIVE:false}", "plot.polar.access-token=test-token", "plot.polar.ai-meter-id=test-meter"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatArtifactProgressIntegrationTest {
	@Autowired private lateinit var bootstrap: DevBootstrapService
	@Autowired private lateinit var dev: DevContext
	@Autowired private lateinit var jdbc: JdbcTemplate
	@Autowired private lateinit var runs: ChatRunService
	@Autowired private lateinit var agentWorker: AgentRunWorker
	@Autowired private lateinit var model: ScriptedAgentRuntime
	@Autowired private lateinit var execution: ArtifactWorkflowExecutionPersistence
	@Autowired private lateinit var query: ArtifactWorkflowQueryPersistence
	@Autowired private lateinit var workflow: ArtifactWorkflowService
	@Autowired private lateinit var progress: ChatRunProgressPersistence
	@Autowired @org.springframework.beans.factory.annotation.Qualifier("artifactWorkflowModelGateway") private lateinit var liveGateway: ArtifactWorkflowModelGateway
	@Autowired private lateinit var transactions: com.plot.api.persistence.TransactionExecutor
	@Autowired private lateinit var completion: ArtifactWorkflowAgentRunCompletionHandler

	@BeforeEach fun isolate() {
		bootstrap.bootstrap(); model.reset()
		jdbc.update("update agent_runs set status='FAILED',failure_code='TEST_ISOLATION',claimed_by=null,finished_at=now() where status in ('QUEUED','RUNNING')")
		jdbc.update("update generation_runs set status='FAILED',error_code='TEST_ISOLATION',claimed_by=null,claimed_at=null,heartbeat_at=null,finished_at=greatest(now(),coalesce(started_at,created_at)) where status in ('QUEUED','WRITING','REVIEWING','REWRITING')")
		jdbc.update("update workspaces set plan='founding',entitlement_status='active',access_mode='full' where id=?",dev.devWorkspaceId)
	}

	@Test fun `draft writes fence workspace claim invocation epoch and terminal`() {
		val (run, generation) = handoff()
		val claim = assertNotNull(execution.claimNext("draft-first", Instant.now().minusSeconds(120)))
		assertEquals(generation, claim.runId)
		val invocation = execution.beginInvocation(claim, ModelRole.WRITER)
		val epoch = assertNotNull(progress.beginArtifact(claim, invocation))
		progress.updateArtifact(claim, invocation, epoch, "WRITING", listOf("first"))
		assertEquals(listOf("first"), progress.load(dev.devWorkspaceId, run)?.draftParagraphs)
		assertFailsWith<IllegalStateException> { progress.updateArtifact(claim.copy(workspaceId=UUID.randomUUID()), invocation, epoch, "WRITING", listOf("cross tenant")) }
		assertFailsWith<IllegalStateException> { progress.updateArtifact(claim, invocation.copy(id=UUID.randomUUID()), epoch, "WRITING", listOf("wrong invocation")) }
		val nextEpoch = assertNotNull(progress.beginArtifact(claim, invocation))
		assertTrue(nextEpoch > epoch)
		assertFailsWith<IllegalStateException> { progress.updateArtifact(claim, invocation, epoch, "WRITING", listOf("old epoch")) }
		val next = assertNotNull(execution.claimNext("draft-new", Instant.now().plusSeconds(120)))
		assertFailsWith<IllegalStateException> { progress.updateArtifact(claim, invocation, nextEpoch, "WRITING", listOf("old claim")) }
		jdbc.update("update agent_runs set status='FAILED',failure_code='TEST_FAILURE',finished_at=now() where id=?",run)
		assertFailsWith<IllegalStateException> { progress.updateArtifact(next, invocation, nextEpoch, "WRITING", listOf("late terminal")) }
	}

	@Test fun `writer preview survives review rewrite and authoritative final projection`() {
		val (run, generation) = handoff()
		val delegate = com.plot.api.routine.AgentArtifactWorkflowModelGateway()
		var reviews = 0
		val gateway = object : ArtifactWorkflowModelGateway by delegate {
			override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> {
				val emit = assertNotNull(request.onText)
				emit("""{"sentences":[{"body":"first preview"},{"body":"partial""")
				val snapshot = assertNotNull(progress.load(dev.devWorkspaceId,run))
				assertEquals("WRITING",snapshot.phase)
				assertEquals(listOf("first preview"),snapshot.draftParagraphs)
				emit(""" second"}]}""")
				return delegate.write(request).copy(value=WriterOutput(listOf(WriterSentence("Validated writer body."))))
			}
			override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> {
				assertEquals("REVIEWING",progress.load(dev.devWorkspaceId,run)?.phase)
				val supported = delegate.review(request)
				return if (reviews++ == 0) supported.copy(value=ReviewerOutput(listOf(SentenceReview(request.sentences.single().id,ReviewVerdict.NEEDS_SUPPORT,emptyList(),"Needs source correction")))) else supported
			}
			override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> {
				assertEquals("REWRITING",progress.load(dev.devWorkspaceId,run)?.phase)
				return ModelCallResult(TargetedRewriteOutput(listOf(TargetedRewrite(request.targetSentenceIds.single(),"Corrected source-backed body."))),delegate.review(ReviewerModelRequest(request.artifactWorkflowRunId,request.sentences,request.evidence)).metadata)
			}
		}
		val worker = artifactWorker(gateway)
		assertTrue(worker.processOne())
		assertEquals(listOf("Validated writer body."),progress.load(dev.devWorkspaceId,run)?.draftParagraphs)
		assertEquals("REVIEWING",progress.load(dev.devWorkspaceId,run)?.phase)
		assertTrue(worker.processOne())
		assertEquals("REWRITING",progress.load(dev.devWorkspaceId,run)?.phase)
		assertTrue(worker.processOne())
		assertEquals(listOf("Corrected source-backed body."),progress.load(dev.devWorkspaceId,run)?.draftParagraphs)
		assertTrue(worker.processOne())
		val final = assertNotNull(progress.load(dev.devWorkspaceId,run))
		assertEquals("COMPLETE",final.phase)
		assertEquals(AgentRunStatus.SUCCEEDED,final.status)
		assertNotNull(final.artifactId)
		assertEquals(ArtifactWorkflowRunStatus.READY,query.loadState(dev.devWorkspaceId,generation).status)
		assertEquals(1,jdbc.queryForObject("select count(*) from content_packs where generation_run_id=?",Int::class.java,generation))
	}

	@Test fun `failed writer retains incomplete preview without final artifact`() {
		val (run, _) = handoff()
		val delegate = com.plot.api.routine.AgentArtifactWorkflowModelGateway()
		val worker = artifactWorker(object : ArtifactWorkflowModelGateway by delegate {
			override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> {
				assertNotNull(request.onText)("""{"sentences":[{"body":"Saved prefix"},{"body":"unfinished""")
				throw ArtifactWorkflowModelException(ModelFailureCode.MALFORMED_OUTPUT, "incomplete", metadata=delegate.write(request).metadata)
			}
		})
		assertTrue(worker.processOne())
		val failed = assertNotNull(progress.load(dev.devWorkspaceId,run))
		assertEquals("FAILED",failed.phase)
		assertEquals(listOf("Saved prefix"),failed.draftParagraphs)
		assertNull(failed.artifactId)
	}

	@Test fun `checkpoint progress and invocation roll back together and ended invocation rejects callbacks`() {
		val (run, generation) = handoff()
		val claim = assertNotNull(execution.claimNext("atomic-draft",Instant.now().minusSeconds(120)))
		val invocation = execution.beginInvocation(claim,ModelRole.WRITER)
		val epoch = assertNotNull(progress.beginArtifact(claim,invocation))
		progress.updateArtifact(claim,invocation,epoch,"WRITING",listOf("durable preview"))
		val state = query.loadState(dev.devWorkspaceId,generation)
		assertFailsWith<IllegalStateException> {
			execution.completeCheckpoint(claim,invocation,state,null) {
				progress.updateArtifact(claim,invocation,epoch,"REVIEWING",listOf("rolled back"))
				throw IllegalStateException("checkpoint fault")
			}
		}
		assertEquals(listOf("durable preview"),progress.load(dev.devWorkspaceId,run)?.draftParagraphs)
		assertEquals("RUNNING",jdbc.queryForObject("select status from model_invocations where id=?",String::class.java,invocation.id))
		jdbc.update("update model_invocations set status='SUCCEEDED',finished_at=now() where id=?",invocation.id)
		assertFailsWith<IllegalStateException> { progress.updateArtifact(claim,invocation,epoch,"WRITING",listOf("late invocation")) }
	}

	@Test fun `needs review materializes existing final artifact without changing completion policy`() {
		val (run,generation) = handoff()
		val delegate = com.plot.api.routine.AgentArtifactWorkflowModelGateway()
		val worker = artifactWorker(object : ArtifactWorkflowModelGateway by delegate {
			override fun review(request: ReviewerModelRequest): ModelCallResult<ReviewerOutput> = delegate.review(request).copy(
				value=ReviewerOutput(listOf(SentenceReview(request.sentences.single().id,ReviewVerdict.NEEDS_SUPPORT,emptyList(),"Still needs support"))))
			override fun rewrite(request: RewriteModelRequest): ModelCallResult<TargetedRewriteOutput> = ModelCallResult(
				TargetedRewriteOutput(listOf(TargetedRewrite(request.targetSentenceIds.single(),"Rewritten draft."))),
				delegate.review(ReviewerModelRequest(request.artifactWorkflowRunId,request.sentences,request.evidence)).metadata)
		})
		repeat(8) { assertTrue(worker.processOne()) }
		val final = assertNotNull(progress.load(dev.devWorkspaceId,run))
		assertEquals(ArtifactWorkflowRunStatus.NEEDS_REVIEW,query.loadState(dev.devWorkspaceId,generation).status)
		assertEquals(AgentRunStatus.SUCCEEDED,final.status)
		assertEquals("COMPLETE",final.phase)
		assertEquals(listOf("Rewritten draft."),final.draftParagraphs)
		assertNotNull(final.artifactId)
	}

	@org.junit.jupiter.api.Tag("live-eval")
	@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="PLOT_EVAL_LIVE",matches="true")
	@Test fun `configured provider streams source backed draft before final checkpoint`() {
		val (run,generation) = handoff()
		var bodySnapshots = 0
		val observed = object : ArtifactWorkflowModelGateway by liveGateway {
			override fun write(request: WriterModelRequest): ModelCallResult<WriterOutput> {
				var previous = emptyList<String>()
				return liveGateway.write(request.copy(onText = { delta ->
					assertNotNull(request.onText)(delta)
					val snapshot = assertNotNull(progress.load(dev.devWorkspaceId,run))
					if (snapshot.draftParagraphs.isNotEmpty() && snapshot.draftParagraphs != previous) {
						assertEquals("WRITING",snapshot.phase)
						assertNull(snapshot.artifactId)
						bodySnapshots++; previous=snapshot.draftParagraphs
					}
				}))
			}
		}
		val worker = artifactWorker(observed)
		val deadline = System.nanoTime()+90_000_000_000
		while (progress.load(dev.devWorkspaceId,run)?.status == AgentRunStatus.RUNNING && System.nanoTime() < deadline) {
			if (!worker.processOne()) Thread.sleep(250) // Existing provider retry may have a future next_attempt_at.
		}
		assertTrue(bodySnapshots > 0,"Actual provider body visible while writer call in flight")
		val state = query.loadState(dev.devWorkspaceId,generation)
		assertTrue(state.status in setOf(ArtifactWorkflowRunStatus.READY,ArtifactWorkflowRunStatus.NEEDS_REVIEW),"status=${state.status} code=${state.failureCode}")
		val final = assertNotNull(progress.load(dev.devWorkspaceId,run))
		assertEquals(state.sentences.sortedBy { it.orderIndex }.map { it.body },final.draftParagraphs)
		assertEquals(AgentRunStatus.SUCCEEDED,final.status)
		assertNotNull(final.artifactId)
		println("LIVE_DRAFT intermediateSnapshots=$bodySnapshots finalSentences=${state.sentences.size} status=${state.status}")
	}

	@Test fun `progress startup and handoff foreign key linking finish concurrently`() {
		val (run,generation) = handoff()
		val claim = assertNotNull(execution.claimNext("concurrent-draft",Instant.now().minusSeconds(120)))
		val invocation = execution.beginInvocation(claim,ModelRole.WRITER)
		val step = assertNotNull(jdbc.queryForObject("select id from agent_steps where agent_run_id=? and generation_run_id=?",UUID::class.java,run,generation))
		jdbc.update("update agent_steps set generation_run_id=null where id=?",step)
		val agentLocked = java.util.concurrent.CountDownLatch(1)
		val linkNow = java.util.concurrent.CountDownLatch(1)
		java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
			val linking = executor.submit<Int> {
				transactions.execute {
					jdbc.queryForObject("select id from agent_runs where workspace_id=? and id=? for update",UUID::class.java,dev.devWorkspaceId,run)
					jdbc.execute("set local statement_timeout='5s'")
					agentLocked.countDown()
					assertTrue(linkNow.await(5,java.util.concurrent.TimeUnit.SECONDS))
					// Same actual FK update as linkArtifactWorkflowStep, while its AgentRun is locked.
					jdbc.update("update agent_steps set generation_run_id=? where workspace_id=? and id=?",generation,dev.devWorkspaceId,step)
				}
			}
			try {
				assertTrue(agentLocked.await(5,java.util.concurrent.TimeUnit.SECONDS))
				val starting = executor.submit<Long?> { progress.beginArtifact(claim,invocation) }
				val deadline = System.nanoTime()+3_000_000_000
				var waiting = false
				while (!waiting && System.nanoTime()<deadline) {
					waiting = jdbc.queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query like '%select a.id,a.status from generation_runs g%')",Boolean::class.java)==true
					if (!waiting) Thread.sleep(10)
				}
				assertTrue(waiting,"Progress guard waits on the held AgentRun after locking generation")
				linkNow.countDown()
				assertEquals(1,linking.get(8,java.util.concurrent.TimeUnit.SECONDS))
				assertNotNull(starting.get(8,java.util.concurrent.TimeUnit.SECONDS))
				assertEquals("WRITING",progress.load(dev.devWorkspaceId,run)?.phase)
			} finally { linkNow.countDown() }
		}
	}

	private fun artifactWorker(gateway: ArtifactWorkflowModelGateway) = ArtifactWorkflowRunWorker(
		executionPersistence=execution,queryPersistence=query,workflowService=workflow,modelGateway=gateway,
		agentRunCompletion=completion,chatProgress=progress,
	)

	private fun handoff(): Pair<UUID, UUID> {
		val (_, namespace, scope) = connectedSource()
		val block = UUID.randomUUID()
		jdbc.update("insert into writing_blocks (id,workspace_id,source_namespace_id,external_object_key,source_origin,source_kind,title,body,url,canonical_url,platform,content_hash,ingested_at,status,created_at,updated_at) values (?,?,?,?,'integration','commit','Orbit release','Orbit ships offline drafts.','https://github.test/orbit','https://github.test/orbit','github',?,now(),'ACTIVE',now(),now())", block,dev.devWorkspaceId,namespace,"commit:$block","hash-$block")
		jdbc.update("insert into writing_block_scopes (id,workspace_id,source_namespace_id,writing_block_id,source_scope_id,membership_kind,status,first_seen_at,last_seen_at) values (?,?,?,?,?,'CONTAINED_IN','ACTIVE',now(),now())",UUID.randomUUID(),dev.devWorkspaceId,namespace,block,scope)
		val run = runs.admit(CreateChatAgentRunRequest("Create a grounded release note", writingBlockIds=listOf(block)), "draft-handoff-${UUID.randomUUID()}")
		model.scriptedDecision = { AgentDecision(AgentDecisionAction.CREATE_ARTIFACT, selectedInputIds=it.inputs.map { input -> input.id }) }
		assertTrue(agentWorker.processOne())
		return run.id to assertNotNull(jdbc.queryForObject("select id from generation_runs where agent_run_id=?",UUID::class.java,run.id))
	}
	private fun connectedSource(): Triple<UUID, UUID, UUID> {
		val connection = UUID.randomUUID(); val namespace = UUID.randomUUID(); val scope = UUID.randomUUID()
		val workspace = dev.devWorkspaceId; val user = dev.devUserId
		jdbc.update("insert into connections (id,workspace_id,provider,connection_kind,external_connection_key,status,created_by_user_id,created_at,updated_at) values (?,?,'GITHUB','GITHUB_APP_INSTALLATION',?,'ACTIVE',?,now(),now())", connection, workspace, UUID.randomUUID().toString(), user)
		jdbc.update("insert into source_namespaces (id,workspace_id,provider,namespace_kind,external_namespace_key,display_name,status,created_at,updated_at) values (?,?,'GITHUB','INSTALLATION',?,'stream source','ACTIVE',now(),now())", namespace, workspace, UUID.randomUUID().toString())
		jdbc.update("insert into connection_namespace_bindings (id,workspace_id,provider,connection_id,source_namespace_id,status,valid_from,created_at,updated_at) values (?,?,'GITHUB',?,?,'ACTIVE',now(),now(),now())", UUID.randomUUID(), workspace, connection, namespace)
		jdbc.update("insert into source_scopes (id,workspace_id,source_namespace_id,provider,scope_semantics,scope_kind,external_scope_key,external_key,display_name,status,status_changed_at,created_at,updated_at) values (?,?,?,'GITHUB','CONTAINER','REPOSITORY',?,?,'stream repo','ACTIVE',now(),now(),now())", scope, workspace, namespace, UUID.randomUUID().toString(), UUID.randomUUID().toString())
		return Triple(connection, namespace, scope)
	}

}
