package com.plot.api.autonomy.github

import com.plot.api.TestcontainersConfiguration
import com.plot.api.autonomy.assessment.*
import com.plot.api.autonomy.opportunity.*
import com.plot.api.autonomy.execution.AutonomyExecutionService
import com.plot.api.autonomy.signal.SignalInbox
import com.plot.api.github.*
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class GitHubAutonomyIntegrationTest {
    @Autowired lateinit var sql: JooqSqlExecutor
    @Autowired lateinit var transactions: JooqTransactionExecutor
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: PlatformTransactionManager
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var inbox: SignalInbox

    @Test
    fun `active tag without published release cannot generate even when model says eligible`() = fixture { f ->
        val services = services(AutonomyMode.ACTIVE)
        assertFalse(services.bridge.shouldPrepare(f.request, f.context, f.evidence))
        val item = assertNotNull(services.opportunities.findBySubject(f.workspace, f.scope, GitHubAutonomyBridge.subject(f.request.tagName)))
        assertEquals(AssessmentDisposition.AWAITING_EVIDENCE, item.disposition)
        assertNull(item.goalId)
        assertEquals("ACTIVE", jdbc.queryForObject("select autonomy_mode from github_release_draft_requests where id=?", String::class.java, f.request.id))
    }

    @Test
    fun `published release creates one goal and replay reuses assessment`() = fixture { f ->
        f.publish()
        val services = services(AutonomyMode.ACTIVE)
        assertTrue(services.bridge.shouldPrepare(f.request, f.context, f.evidence))
        assertTrue(services.bridge.shouldPrepare(f.request, f.context, f.evidence))
        val item = assertNotNull(services.opportunities.findBySubject(f.workspace, f.scope, GitHubAutonomyBridge.subject(f.request.tagName)))
        assertEquals(AssessmentDisposition.ELIGIBLE, item.disposition)
        assertNotNull(item.goalId)
        assertEquals(1, services.calls.get())
        assertEquals(1, count("autonomy_goals", f.workspace))
        assertNull(services.opportunities.find(UUID.randomUUID(), item.id))
    }

    @Test
    fun `shadow records assessment without goal and pins ownership for retry`() = fixture { f ->
        f.publish()
        val shadow = services(AutonomyMode.SHADOW)
        assertTrue(shadow.bridge.shouldPrepare(f.request, f.context, f.evidence))
        assertEquals(0, count("autonomy_goals", f.workspace))
        // A rollout change cannot take over a request that already chose shadow ownership.
        assertTrue(services(AutonomyMode.ACTIVE).bridge.shouldPrepare(f.request, f.context, f.evidence))
        assertEquals(0, count("autonomy_goals", f.workspace))
        assertEquals("SHADOW", jdbc.queryForObject("select autonomy_mode from github_release_draft_requests where id=?", String::class.java, f.request.id))
    }

    @Test
    fun `duplicate observation projects one waiting opportunity and preserves dismissed decision`() = fixture { f ->
        val services = services(AutonomyMode.ACTIVE)
        val webhook = ParsedGitHubWebhook(f.delivery.externalDeliveryId, "push", null, f.context.installationId,
            f.context.repositoryId, ref="refs/tags/v1", beforeSha="a".repeat(40), afterSha="b".repeat(40),
            tagName="v1", refCreated=true, refDeleted=false, forced=false, payloadHash="c".repeat(64))
        services.bridge.observe(f.context, f.delivery, webhook)
        services.bridge.observe(f.context, f.delivery, webhook)
        val projection = GitHubSignalProjection(inbox, services.opportunities, sql, transactions, AutonomyProperties(AutonomyMode.ACTIVE), AutonomyExecutionService(sql))
        assertTrue(projection.projectNext())
        val item = assertNotNull(services.opportunities.findBySubject(f.workspace, f.scope, "github-release:v1"))
        assertEquals(AssessmentDisposition.AWAITING_EVIDENCE, item.disposition)
        val dismissed = services.opportunities.dismiss(f.workspace, item.id, item.version)
        services.bridge.observe(f.context, f.delivery, webhook)
        assertEquals(1, count("autonomy_signals", f.workspace))
        assertEquals(1, count("autonomy_opportunities", f.workspace))
        assertEquals("SUCCEEDED", jdbc.queryForObject("select state from autonomy_signals where workspace_id=?", String::class.java, f.workspace))
        assertTrue(assertNotNull(services.opportunities.find(f.workspace, dismissed.id)).dismissed)
        assertEquals(0, services.calls.get())
    }

    @Test
    fun `published event requeues deferred active request without creating another request`() = fixture { f ->
        val services = services(AutonomyMode.ACTIVE)
        assertFalse(services.bridge.shouldPrepare(f.request, f.context, f.evidence))
        jdbc.update("update github_release_draft_requests set status='DEFERRED',finished_at=now() where id=?", f.request.id)
        f.publish()
        val webhook = ParsedGitHubWebhook(f.delivery.externalDeliveryId, "release", "published", f.context.installationId,
            f.context.repositoryId, ref=null, beforeSha=null, afterSha=null, tagName="v1", refCreated=null,
            refDeleted=null, forced=null, payloadHash="c".repeat(64))
        services.bridge.observe(f.context, f.delivery, webhook)
        assertEquals("QUEUED", jdbc.queryForObject("select status from github_release_draft_requests where id=?", String::class.java, f.request.id))
        assertEquals(1, count("github_release_draft_requests", f.workspace))
        assertTrue(services.bridge.shouldPrepare(f.request.copy(transitionVersion=1), f.context, f.evidence))
        assertEquals(2, services.calls.get())
    }

    @Test
    fun `admission rollback is atomic replay maps once and reconciliation requires an artifact`() = fixture { f ->
        f.publish()
        val services = services(AutonomyMode.ACTIVE)
        assertTrue(services.bridge.shouldPrepare(f.request, f.context, f.evidence))
        val goal = assertNotNull(services.opportunities.findBySubject(f.workspace, f.scope, "github-release:v1")?.goalId)
        val agent = f.createAgent()
        assertFailsWith<IllegalStateException> {
            transactions.execute {
                services.bridge.admitted(f.request, agent)
                error("Abort admission transaction")
            }
        }
        assertEquals(0, count("autonomy_tasks", f.workspace))
        assertEquals(0, count("autonomy_executions", f.workspace))
        assertNull(services.opportunities.goalByOpportunity(f.workspace,
            assertNotNull(services.opportunities.findBySubject(f.workspace, f.scope, "github-release:v1")).id)?.agentRunId)
        transactions.execute { services.bridge.admitted(f.request, agent) }
        val execution = jdbc.queryForObject("select id from autonomy_executions where workspace_id=?", UUID::class.java, f.workspace)
        transactions.execute { services.bridge.admitted(f.request, agent) }
        assertEquals(execution, jdbc.queryForObject("select id from autonomy_executions where workspace_id=?", UUID::class.java, f.workspace))
        assertEquals(1, count("autonomy_tasks", f.workspace))
        val executions = AutonomyExecutionService(sql)
        assertFailsWith<OpportunityException> { transactions.execute { executions.admit(UUID.randomUUID(), goal, agent) } }
        jdbc.update("update agent_runs set status='SUCCEEDED' where id=?", agent)
        transactions.execute { executions.reconcile(f.workspace) }
        assertEquals("RUNNING", jdbc.queryForObject("select state from autonomy_executions where id=?", String::class.java, execution))
        jdbc.update("update agent_runs set status='FAILED' where id=?", agent)
        transactions.execute { executions.reconcile(f.workspace) }
        assertEquals("FAILED", jdbc.queryForObject("select state from autonomy_executions where id=?", String::class.java, execution))
        assertEquals("FAILED", jdbc.queryForObject("select state from autonomy_tasks where workspace_id=?", String::class.java, f.workspace))
        assertEquals("FAILED", jdbc.queryForObject("select state from autonomy_goals where id=?", String::class.java, goal))
        for (attempt in 1..2) {
            val retry = f.request.copy(runAttempt=attempt)
            assertTrue(services.bridge.shouldPrepare(retry, f.context, f.evidence))
            val nextAgent = f.createAgent()
            transactions.execute { services.bridge.admitted(retry, nextAgent) }
            assertEquals(attempt+1, jdbc.queryForObject("select max(task_version) from autonomy_tasks where workspace_id=?", Int::class.java, f.workspace))
            transactions.execute { executions.reconcile(f.workspace) }
            assertEquals("RUNNING", jdbc.queryForObject("select state from autonomy_goals where id=?", String::class.java, goal))
            assertEquals("FAILED", jdbc.queryForObject("select state from autonomy_executions where id=?", String::class.java, execution))
            jdbc.update("update agent_runs set status='FAILED' where id=?", nextAgent)
            // Retry immediately, before the periodic autonomy reconciler has observed the failure.
            assertEquals("RUNNING", jdbc.queryForObject("select state from autonomy_goals where id=?", String::class.java, goal))
        }
        assertFalse(services.bridge.shouldPrepare(f.request.copy(runAttempt=3), f.context, f.evidence))
        assertEquals(3, count("autonomy_tasks", f.workspace))
    }

    @Test
    fun `shadow model outage records failure without blocking existing preparation`() = fixture { f ->
        f.publish()
        val opportunities = OpportunityService(sql, tx, mapper, CustomerValueAssessmentService(AssessmentGateway {
            throw AssessmentException("ASSESSMENT_PROVIDER_UNAVAILABLE", true)
        }, AssessmentProperties()), OpportunityProperties())
        val bridge = GitHubAutonomyBridge(AutonomyProperties(AutonomyMode.SHADOW), opportunities, inbox, sql,
            transactions, mapper, AutonomyExecutionService(sql))
        assertTrue(bridge.shouldPrepare(f.request, f.context, f.evidence))
        val item = assertNotNull(opportunities.findBySubject(f.workspace, f.scope, "github-release:v1"))
        assertEquals("ASSESSMENT_PROVIDER_UNAVAILABLE", item.lastErrorCode)
        assertEquals(0, count("autonomy_goals", f.workspace))
    }

    @Test
    fun `held release wakes once for changed context but not elapsed time`() = fixture { f ->
        val services = services(AutonomyMode.ACTIVE)
        assertFalse(services.bridge.shouldPrepare(f.request, f.context, f.evidence))
        jdbc.update("update github_release_draft_requests set status='DEFERRED',finished_at=now() where id=?", f.request.id)
        val projection = GitHubSignalProjection(inbox, services.opportunities, sql, transactions,
            AutonomyProperties(AutonomyMode.ACTIVE), AutonomyExecutionService(sql))
        assertEquals(0, projection.requeueChangedContext())
        jdbc.update("update source_scopes set status_changed_at=status_changed_at+interval '1 second' where id=?", f.scope)
        assertEquals(1, projection.requeueChangedContext())
        assertEquals(0, projection.requeueChangedContext())
        assertEquals("QUEUED", jdbc.queryForObject("select status from github_release_draft_requests where id=?", String::class.java, f.request.id))
        assertFalse(services.bridge.shouldPrepare(f.request.copy(transitionVersion=1), f.context, f.evidence))
        assertEquals(0, count("autonomy_goals", f.workspace))
    }

    @Test
    fun `release unpublication overrides earlier publication evidence`() = fixture { f ->
        f.publish()
        val removal = UUID.randomUUID()
        jdbc.update("""insert into github_webhook_deliveries(id,external_delivery_id,event_type,event_action,installation_id,repository_id,tag_name,payload_hash,disposition,received_at)
            values (?,?,'release','unpublished',?,?,'v1',?,'OBSERVED',now())""",removal,removal.toString(),f.context.installationId,f.context.repositoryId,"e".repeat(64))
        val services = services(AutonomyMode.ACTIVE)
        assertFalse(services.bridge.shouldPrepare(f.request, f.context, f.evidence))
        assertEquals(AssessmentDisposition.AWAITING_EVIDENCE,
            services.opportunities.findBySubject(f.workspace, f.scope, "github-release:v1")?.disposition)
        assertEquals(0, count("autonomy_goals", f.workspace))
    }

    @Test
    fun `freed capacity wakes cached eligible release without another model call`() = fixture { f ->
        f.publish()
        val limits=OpportunityProperties(activeGoalLimit=1)
        val services=services(AutonomyMode.ACTIVE,limits)
        val other=services.opportunities.assess(f.workspace,f.scope,"other","Other release",AssessmentInput(
            f.scope,"1",listOf(AssessmentEvidence("other","1",AssessmentEvidenceKind.RELEASE,"Other","Customer improvement",CustomerAvailability.AVAILABLE)),"Product"))
        assertFalse(services.bridge.shouldPrepare(f.request,f.context,f.evidence))
        jdbc.update("update github_release_draft_requests set status='DEFERRED',finished_at=now() where id=?",f.request.id)
        val projection=GitHubSignalProjection(inbox,services.opportunities,sql,transactions,AutonomyProperties(AutonomyMode.ACTIVE),AutonomyExecutionService(sql),limits=limits)
        assertEquals(0,projection.requeueChangedContext())
        jdbc.update("update autonomy_goals set state='FAILED' where id=?",other.goalId)
        assertEquals(1,projection.requeueChangedContext())
        assertTrue(services.bridge.shouldPrepare(f.request.copy(transitionVersion=1,runAttempt=1),f.context,f.evidence))
        assertEquals(2,services.calls.get())
    }

    private data class Services(val bridge: GitHubAutonomyBridge, val opportunities: OpportunityService, val calls: AtomicInteger)

    private fun services(mode: AutonomyMode, limits: OpportunityProperties = OpportunityProperties()): Services {
        val calls = AtomicInteger()
        val opportunities = OpportunityService(sql, tx, mapper, CustomerValueAssessmentService(AssessmentGateway { input ->
            calls.incrementAndGet()
            AssessmentDecision(AssessmentDisposition.ELIGIBLE, "Customers can use the released improvement", input.evidence.map { it.id }, emptyList())
        }, AssessmentProperties()), limits)
        return Services(GitHubAutonomyBridge(AutonomyProperties(mode), opportunities, inbox, sql, transactions, mapper, AutonomyExecutionService(sql)), opportunities, calls)
    }

    private fun count(table: String, workspace: UUID) = jdbc.queryForObject("select count(*) from $table where workspace_id=?", Int::class.java, workspace)

    private inner class Fixture(val workspace: UUID, val scope: UUID, val context: GitHubReleaseSourceContext,
        val delivery: GitHubWebhookDelivery, val request: GitHubReleaseDraftRequest, blockId: UUID) {
        val evidence = GitHubReleaseEvidence(UUID.randomUUID(), listOf(blockId))
        fun createAgent(): UUID {
            val session=UUID.randomUUID(); val agent=UUID.randomUUID()
            jdbc.update("insert into users(id,email,display_name,status,created_at,updated_at) values (?,?,'Autonomy test','ACTIVE',now(),now()) on conflict (id) do nothing",
                context.createdByUserId, "${context.createdByUserId}@example.test")
            jdbc.update("insert into work_sessions(id,workspace_id,title,status,created_by_user_id,created_at,updated_at) values (?,?,'Draft','ACTIVE',?,now(),now())",
                session,workspace,context.createdByUserId)
            jdbc.update("""insert into agent_runs(id,workspace_id,work_session_id,created_by_user_id,instruction_snapshot,prompt_version,tool_policy_version,
                budget_snapshot,status,origin,idempotency_key,request_fingerprint,created_at,updated_at)
                values (?,?,?,?,'Prepare draft','test-v1','test-v1','{}'::jsonb,'QUEUED','CHAT',?,?,now(),now())""",
                agent,workspace,session,context.createdByUserId,agent.toString(),agent.toString())
            return agent
        }
        fun publish() {
            jdbc.update("update github_webhook_deliveries set event_type='release',event_action='published',tag_name='v1' where id=?", delivery.id)
        }
    }

    private fun fixture(action: (Fixture) -> Unit) {
        val workspace=UUID.randomUUID(); val namespace=UUID.randomUUID(); val scope=UUID.randomUUID()
        val connection=UUID.randomUUID(); val binding=UUID.randomUUID(); val deliveryId=UUID.randomUUID(); val requestId=UUID.randomUUID()
        val blockId=UUID.randomUUID()
        val repositoryId=System.nanoTime(); val installationId=repositoryId
        jdbc.update("insert into workspaces(id,name,slug,status,created_at,updated_at) values (?,'GitHub autonomy',?,'ACTIVE',now(),now())",workspace,workspace.toString())
        jdbc.update("""insert into source_namespaces(id,workspace_id,provider,namespace_kind,external_namespace_key,status,created_at,updated_at)
            values (?,?,'GITHUB','INSTALLATION',?,'ACTIVE',now(),now())""",namespace,workspace,namespace.toString())
        jdbc.update("""insert into source_scopes(id,workspace_id,source_namespace_id,provider,scope_semantics,scope_kind,external_scope_key,display_name,status,created_at,updated_at)
            values (?,?,?,'GITHUB','CONTAINER','REPOSITORY',?,'Repo','ACTIVE',now(),now())""",scope,workspace,namespace,scope.toString())
        jdbc.update("""insert into connections(id,workspace_id,provider,connection_kind,external_connection_key,status,created_at,updated_at)
            values (?,?,'GITHUB','APP_INSTALLATION',?,'ACTIVE',now(),now())""",connection,workspace,connection.toString())
        jdbc.update("""insert into connection_namespace_bindings(id,workspace_id,provider,connection_id,source_namespace_id,status,valid_from,created_at,updated_at)
            values (?,?,'GITHUB',?,?,'ACTIVE',now(),now(),now())""",binding,workspace,connection,namespace)
        jdbc.update("""insert into github_webhook_deliveries(id,external_delivery_id,event_type,installation_id,repository_id,tag_name,payload_hash,disposition,received_at)
            values (?,?,'push',?,?,'v1',?,'OBSERVED',now())""",deliveryId,deliveryId.toString(),installationId,repositoryId,"c".repeat(64))
        jdbc.update("""insert into github_release_draft_requests(id,workspace_id,source_scope_id,initial_delivery_id,tag_name,base_sha,head_sha,status,created_at,updated_at)
            values (?,?,?,?,'v1',?,?,'RESOLVING',now(),now())""",requestId,workspace,scope,deliveryId,"a".repeat(40),"b".repeat(40))
        jdbc.update("""insert into writing_blocks(id,workspace_id,source_namespace_id,external_object_key,source_origin,source_kind,title,body,content_hash,status,ingested_at,created_at,updated_at)
            values (?,?,?,'pr:42','IMPORTED','PULL_REQUEST','Restore customer login','Customers can sign in again after reconnecting their account',?,'ACTIVE',now(),now(),now())""",blockId,workspace,namespace,"d".repeat(64))
        jdbc.update("""insert into writing_block_scopes(id,workspace_id,source_namespace_id,writing_block_id,source_scope_id,membership_kind,status,first_seen_at,last_seen_at)
            values (?,?,?,?,?,'CONTAINED_IN','ACTIVE',now(),now())""",UUID.randomUUID(),workspace,namespace,blockId,scope)
        val context=GitHubReleaseSourceContext(workspace,UUID.randomUUID(),connection,binding,namespace,scope,installationId,repositoryId,"plot","repo","main")
        val delivery=GitHubWebhookDelivery(deliveryId,deliveryId.toString(),"push",null,installationId,repositoryId,null,null,null,"v1",null,null,null,"c".repeat(64),GitHubWebhookDisposition.OBSERVED,null,Instant.now(),null)
        val request=GitHubReleaseDraftRequest(requestId,workspace,scope,deliveryId,"v1","a".repeat(40),"b".repeat(40),null,GitHubReleaseDraftStatus.RESOLVING,0,0,null,null,null)
        try { action(Fixture(workspace,scope,context,delivery,request,blockId)) } finally {
            listOf("autonomy_executions","autonomy_tasks","autonomy_goals","autonomy_assessments","autonomy_opportunities","autonomy_missions","autonomy_daily_budgets",
                "autonomy_signal_heads","autonomy_signals","github_release_draft_requests","agent_runs","work_sessions","writing_block_scopes","writing_blocks","source_scopes","connection_namespace_bindings","connections","source_namespaces").forEach {
                jdbc.update("delete from $it where workspace_id=?",workspace)
            }
            jdbc.update("delete from github_webhook_deliveries where installation_id=? and repository_id=?",installationId,repositoryId)
            jdbc.update("delete from workspaces where id=?",workspace)
            jdbc.update("delete from users where id=?",context.createdByUserId)
        }
    }
}
