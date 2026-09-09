package com.plot.api.autonomy.opportunity

import com.plot.api.TestcontainersConfiguration
import com.plot.api.autonomy.assessment.*
import com.plot.api.persistence.JooqSqlExecutor
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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
class OpportunityServiceIntegrationTest {
    @Autowired lateinit var sql: JooqSqlExecutor
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: PlatformTransactionManager
    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `exclusion is cached and manual dismissal survives new input until restore`() = fixture { workspace, scope ->
        val calls = AtomicInteger()
        val service = service(calls, AssessmentDisposition.EXCLUDED)
        val input = input(scope)
        val first = service.assess(workspace, scope, "release:1", "Release", input)
        assertNull(first.goalId)
        assertEquals(first.id, service.assess(workspace, scope, "release:1", "Release", input).id)
        assertEquals(1, calls.get())
        val dismissed = service.dismiss(workspace, first.id, first.version)
        val changed = input.copy(contextRevision = "2")
        assertTrue(service.assess(workspace, scope, "release:1", "Release", changed).dismissed)
        assertEquals(1, calls.get())
        assertFailsWith<OpportunityException> { service.restore(workspace, first.id, first.version) }
        service.restore(workspace, first.id, dismissed.version)
        service.assess(workspace, scope, "release:1", "Release", changed)
        assertEquals(2, calls.get())
    }

    @Test
    fun `eligible replay creates exactly one goal and frozen input survives changes`() = fixture { workspace, scope ->
        val calls = AtomicInteger()
        val service = service(calls, AssessmentDisposition.ELIGIBLE)
        val first = service.assess(workspace, scope, "release:1", "Release", input(scope))
        assertNotNull(first.goalId)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..8).map { Callable {
                service.assess(workspace, scope, "release:1", "Release", input(scope))
            } }).map { it.get() }
            assertTrue(results.all { it.goalId == first.goalId })
            assertEquals(1, calls.get())
        } finally { pool.shutdownNow() }
        jdbc.update("update autonomy_goals set state='RUNNING' where id=?",first.goalId)
        val frozen = jdbc.queryForObject("select input_snapshot::text from autonomy_goals where id = ?", String::class.java, first.goalId)
        service.assess(workspace, scope, "release:1", "Release", input(scope).copy(contextRevision = "2"))
        assertEquals(frozen, jdbc.queryForObject("select input_snapshot::text from autonomy_goals where id = ?", String::class.java, first.goalId))
        assertEquals(1, jdbc.queryForObject("select count(*) from autonomy_goals where workspace_id = ?", Int::class.java, workspace))
    }

    @Test
    fun `prepare cannot bypass customer availability or disabled scope`() = fixture { workspace, scope ->
        val service = service(AtomicInteger(), AssessmentDisposition.ELIGIBLE)
        val unavailable = input(scope).let { it.copy(evidence = it.evidence.map { evidence -> evidence.copy(availability = CustomerAvailability.UNKNOWN) }) }
        val item = service.assess(workspace, scope, "release:1", "Release", unavailable)
        assertEquals(AssessmentDisposition.AWAITING_EVIDENCE, item.disposition)
        assertFailsWith<OpportunityException> { service.prepare(workspace, item.id, item.version) }
        jdbc.update("update source_scopes set status = 'DISABLED' where id = ?", scope)
        assertFailsWith<OpportunityException> { service.assess(workspace, scope, "release:1", "Release", unavailable) }
    }

    @Test
    fun `first concurrent evaluations claim one bounded model invocation`() = fixture { workspace, scope ->
        val calls = AtomicInteger()
        val service = OpportunityService(sql, tx, mapper, CustomerValueAssessmentService(AssessmentGateway { input ->
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            calls.incrementAndGet()
            Thread.sleep(150)
            AssessmentDecision(AssessmentDisposition.ELIGIBLE, "Customer login restored", input.evidence.map { it.id }, emptyList())
        }, AssessmentProperties()), OpportunityProperties())
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..8).map { Callable {
                runCatching { service.assess(workspace, scope, "release:1", "Release", input(scope)) }
            } }).map { it.get() }
            assertEquals(1, calls.get())
            assertTrue(results.any { it.isSuccess })
            assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as? OpportunityException)?.code == "ASSESSMENT_IN_PROGRESS" })
            assertEquals(1, jdbc.queryForObject("select count(*) from autonomy_goals where workspace_id=?", Int::class.java, workspace))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `eligible assessment always creates a goal and repeated preparation reuses it`() = fixture { workspace, scope ->
        val calls = AtomicInteger()
        val service = service(calls, AssessmentDisposition.ELIGIBLE)
        val item = service.assess(workspace,scope,"release:1","Release",input(scope))
        assertNotNull(item.goalId)
        assertEquals(item.goalId, service.prepare(workspace,item.id,item.version).goalId)
        assertEquals(1,calls.get())
    }

    @Test
    fun `failed assessment remains retryable without becoming excluded`() = fixture { workspace, scope ->
        val service = OpportunityService(sql,tx,mapper,CustomerValueAssessmentService(AssessmentGateway {
            throw AssessmentException("ASSESSMENT_PROVIDER_UNAVAILABLE",true)
        },AssessmentProperties()),OpportunityProperties())
        assertFailsWith<AssessmentException> { service.assess(workspace,scope,"release:1","Release",input(scope)) }
        val item = assertNotNull(service.findBySubject(workspace,scope,"release:1"))
        assertEquals(AssessmentDisposition.AWAITING_EVIDENCE,item.disposition)
        assertEquals("ASSESSMENT_PROVIDER_UNAVAILABLE",item.lastErrorCode)
        assertNull(item.goalId)
    }

    @Test
    fun `mission bootstrap preserves pause and goal limit preserves cached assessment`() = fixture { workspace, scope ->
        val calls=AtomicInteger()
        val service=OpportunityService(sql,tx,mapper,CustomerValueAssessmentService(AssessmentGateway { input ->
            calls.incrementAndGet()
            AssessmentDecision(AssessmentDisposition.ELIGIBLE,"Customer-visible fix",input.evidence.map { it.id },emptyList())
        },AssessmentProperties()),OpportunityProperties(activeGoalLimit=1))
        val mission=service.ensureMission(workspace,scope)
        jdbc.update("update autonomy_missions set state='PAUSED' where id=?",mission.id)
        assertEquals("PAUSED",service.ensureMission(workspace,scope).state)
        assertFailsWith<OpportunityException> { service.assess(workspace,scope,"release:1","Release",input(scope)) }
        assertEquals(0,calls.get())
        jdbc.update("update autonomy_missions set state='ACTIVE' where id=?",mission.id)
        service.assess(workspace,scope,"release:1","Release",input(scope))
        val pending=service.assess(workspace,scope,"release:2","Release",input(scope).copy(contextRevision="2"))
        assertNull(pending.goalId)
        assertEquals("ACTIVE_GOAL_LIMIT",pending.lastErrorCode)
        service.assess(workspace,scope,"release:2","Release",input(scope).copy(contextRevision="2"))
        assertEquals(2,calls.get())
    }

    @Test
    fun `completed goal history survives new fingerprint and dismissed queued goal can resume`() = fixture { workspace, scope ->
        val service=service(AtomicInteger(),AssessmentDisposition.ELIGIBLE)
        val first=service.assess(workspace,scope,"release:1","Release",input(scope))
        val dismissed=service.dismiss(workspace,first.id,first.version)
        val restored=service.restore(workspace,first.id,dismissed.version)
        val resumed=service.prepare(workspace,first.id,restored.version)
        assertEquals(first.goalId,resumed.goalId)
        assertEquals("QUEUED",resumed.goalState)
        jdbc.update("update autonomy_goals set state='SUCCEEDED' where id=?",first.goalId)
        val next=service.assess(workspace,scope,"release:1","Release",input(scope).copy(contextRevision="2"))
        assertNotEquals(first.goalId,next.goalId)
        assertEquals(2,jdbc.queryForObject("select count(*) from autonomy_goals where workspace_id=?",Int::class.java,workspace))
    }

    @Test
    fun `expired model claim cannot persist a decision without replacement`() = fixture { workspace, scope ->
        val service=OpportunityService(sql,tx,mapper,CustomerValueAssessmentService(AssessmentGateway { input ->
            jdbc.update("update autonomy_assessments set lease_until=now()-interval '1 second' where workspace_id=?",workspace)
            AssessmentDecision(AssessmentDisposition.ELIGIBLE,"Customer-visible fix",input.evidence.map { it.id },emptyList())
        },AssessmentProperties()),OpportunityProperties())
        val failure=assertFailsWith<OpportunityException> { service.assess(workspace,scope,"release:1","Release",input(scope)) }
        assertEquals("ASSESSMENT_CLAIM_LOST",failure.code)
        val item=assertNotNull(service.findBySubject(workspace,scope,"release:1"))
        assertNull(item.goalId)
        assertNull(item.fingerprint)
    }

    @Test
    fun `admission refuses stale frozen goal and no longer eligible input`() = fixture { workspace, scope ->
        val service=service(AtomicInteger(),AssessmentDisposition.ELIGIBLE)
        val first=service.assess(workspace,scope,"release:1","Release",input(scope))
        jdbc.update("update autonomy_goals set state='RUNNING' where id=?",first.goalId)
        service.assess(workspace,scope,"release:1","Release",input(scope).copy(contextRevision="2"))
        assertFailsWith<OpportunityException> { service.linkAgent(workspace,assertNotNull(first.goalId),UUID.randomUUID()) }
        this.service(AtomicInteger(),AssessmentDisposition.EXCLUDED).assess(workspace,scope,"release:1","Release",input(scope).copy(contextRevision="3"))
        assertFailsWith<OpportunityException> { service.linkAgent(workspace,assertNotNull(first.goalId),UUID.randomUUID()) }
        assertEquals("RUNNING",service.find(workspace,first.id)?.goalState)
    }

    @Test
    fun `daily assessment budget persists across service instances`() = fixture { workspace, scope ->
        val calls=AtomicInteger()
        fun instance()=OpportunityService(sql,tx,mapper,CustomerValueAssessmentService(AssessmentGateway { input ->
            calls.incrementAndGet()
            AssessmentDecision(AssessmentDisposition.EXCLUDED,"Internal-only change",input.evidence.map { it.id },emptyList())
        },AssessmentProperties()),OpportunityProperties(dailyAssessmentLimit=1))
        instance().assess(workspace,scope,"release:1","Release",input(scope))
        val failure=assertFailsWith<OpportunityException> {
            instance().assess(workspace,scope,"release:2","Release",input(scope).copy(contextRevision="2"))
        }
        assertEquals("ASSESSMENT_DAILY_LIMIT",failure.code)
        assertEquals(1,calls.get())
        assertEquals(1,jdbc.queryForObject("select assessment_count from autonomy_daily_budgets where workspace_id=?",Int::class.java,workspace))
    }

    private fun service(calls: AtomicInteger, disposition: AssessmentDisposition) = OpportunityService(sql, tx, mapper,
        CustomerValueAssessmentService(AssessmentGateway { input ->
            calls.incrementAndGet()
            AssessmentDecision(disposition, "Customer outcome evidence", input.evidence.map { it.id }, emptyList())
        }, AssessmentProperties()), OpportunityProperties())

    private fun input(scope: UUID) = AssessmentInput(scope, "1", listOf(AssessmentEvidence("release-1", "1",
        AssessmentEvidenceKind.RELEASE, "Release", "Customer login fixed", CustomerAvailability.AVAILABLE)), "Product")

    private fun fixture(action: (UUID, UUID) -> Unit) {
        val workspace = UUID.randomUUID(); val namespace = UUID.randomUUID(); val scope = UUID.randomUUID()
        jdbc.update("insert into workspaces (id,name,slug,status,created_at,updated_at) values (?,'Opportunity test',?,'ACTIVE',now(),now())", workspace, workspace.toString())
        jdbc.update("""insert into source_namespaces (id,workspace_id,provider,namespace_kind,external_namespace_key,status,created_at,updated_at)
            values (?,?,'TEST','TENANT',?,'ACTIVE',now(),now())""", namespace,workspace,namespace.toString())
        jdbc.update("""insert into source_scopes (id,workspace_id,source_namespace_id,provider,scope_semantics,scope_kind,external_scope_key,display_name,status,created_at,updated_at)
            values (?,?,?,'TEST','CONTAINER','PROJECT',?,'Test','ACTIVE',now(),now())""",scope,workspace,namespace,scope.toString())
        val connection = UUID.randomUUID()
        jdbc.update("""insert into connections(id,workspace_id,provider,connection_kind,external_connection_key,status,created_at,updated_at)
            values (?,?,'TEST','TOKEN',?,'ACTIVE',now(),now())""",connection,workspace,connection.toString())
        jdbc.update("""insert into connection_namespace_bindings(id,workspace_id,provider,connection_id,source_namespace_id,status,valid_from,created_at,updated_at)
            values (?,?,'TEST',?,?,'ACTIVE',now(),now(),now())""",UUID.randomUUID(),workspace,connection,namespace)
        try { action(workspace, scope) } finally {
            listOf("autonomy_goals", "autonomy_assessments", "autonomy_opportunities", "autonomy_missions", "autonomy_daily_budgets", "source_scopes", "connection_namespace_bindings", "connections", "source_namespaces").forEach {
                jdbc.update("delete from $it where workspace_id = ?", workspace)
            }
            jdbc.update("delete from workspaces where id = ?", workspace)
        }
    }
}
