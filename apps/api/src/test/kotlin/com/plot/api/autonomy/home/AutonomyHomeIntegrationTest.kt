package com.plot.api.autonomy.home

import com.plot.api.TestcontainersConfiguration
import com.plot.api.autonomy.assessment.AssessmentDecision
import com.plot.api.autonomy.assessment.AssessmentDisposition
import com.plot.api.autonomy.assessment.AssessmentEvidence
import com.plot.api.autonomy.assessment.AssessmentEvidenceKind
import com.plot.api.autonomy.assessment.AssessmentGateway
import com.plot.api.autonomy.assessment.AssessmentInput
import com.plot.api.autonomy.assessment.AssessmentProperties
import com.plot.api.autonomy.assessment.CustomerAvailability
import com.plot.api.autonomy.assessment.CustomerValueAssessmentService
import com.plot.api.autonomy.opportunity.OpportunityProperties
import com.plot.api.autonomy.opportunity.OpportunityRecord
import com.plot.api.autonomy.opportunity.OpportunityService
import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.persistence.JooqSqlExecutor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AutonomyHomeIntegrationTest {
    @Autowired lateinit var sql: JooqSqlExecutor
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var mapper: ObjectMapper
    private val workspaces = mutableSetOf<UUID>()

    @AfterEach
    fun cleanup() {
        workspaces.forEach { workspace ->
            listOf("autonomy_goals", "autonomy_assessments", "autonomy_opportunities", "autonomy_missions",
                "autonomy_daily_budgets", "source_scopes", "connection_namespace_bindings", "connections", "source_namespaces").forEach {
                jdbc.update("delete from $it where workspace_id=?", workspace)
            }
            jdbc.update("delete from workspaces where id=?", workspace)
        }
        workspaces.clear()
    }

    @Test
    fun `home is not cached and only exposes selected workspace with current source access`() {
        val selected = fixture()
        val revoked = fixture(selected.record.workspaceId)
        val other = fixture()
        jdbc.update("update connection_namespace_bindings set status='REVOKED' where id=?", revoked.bindingId)
        val context = mock(DevContext::class.java)
        val access = mock(WorkspaceAccessService::class.java)
        `when`(context.devWorkspaceId).thenReturn(selected.record.workspaceId)
        val controller = controller(context, access)

        val response = controller.home()

        assertEquals("no-store", response.headers.cacheControl)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(listOf(selected.record.id), assertNotNull(response.body).items.map { it.id })
        verify(access).requireActiveWorkspace(selected.record.workspaceId)

        `when`(context.devWorkspaceId).thenReturn(other.record.workspaceId)
        assertEquals(listOf(other.record.id), assertNotNull(controller.home().body).items.map { it.id })
        verify(access).requireActiveWorkspace(other.record.workspaceId)
    }

    @Test
    fun `dismiss and restore require writable access and stale version maps to conflict`() {
        val fixture = fixture()
        val context = mock(DevContext::class.java)
        val access = mock(WorkspaceAccessService::class.java)
        `when`(context.devWorkspaceId).thenReturn(fixture.record.workspaceId)
        val controller = controller(context, access)
        val initialVersion = AutonomyHomeController.VersionRequest(fixture.record.version)

        val dismissed = controller.dismiss(fixture.record.id, initialVersion)

        assertEquals("no-store", dismissed.headers.cacheControl)
        assertTrue(assertNotNull(dismissed.body).dismissed)
        val conflict = assertFailsWith<ApiException> { controller.dismiss(fixture.record.id, initialVersion) }
        assertEquals(HttpStatus.CONFLICT, conflict.status)
        assertEquals("OPPORTUNITY_VERSION_CONFLICT", conflict.error)
        val restored = controller.restore(fixture.record.id,
            AutonomyHomeController.VersionRequest(assertNotNull(dismissed.body).version))
        assertEquals("no-store", restored.headers.cacheControl)
        assertFalse(assertNotNull(restored.body).dismissed)
        assertTrue(assertNotNull(restored.body).version > assertNotNull(dismissed.body).version)
        verify(access, times(3)).requireWritable(fixture.record.workspaceId)
    }

    @Test
    fun `read only access rejects mutations before changing opportunity`() {
        val fixture = fixture()
        val context = mock(DevContext::class.java)
        val access = mock(WorkspaceAccessService::class.java)
        `when`(context.devWorkspaceId).thenReturn(fixture.record.workspaceId)
        doThrow(ApiException(HttpStatus.FORBIDDEN,"WORKSPACE_READ_ONLY","Read only"))
            .`when`(access).requireWritable(fixture.record.workspaceId)
        val controller = controller(context, access)
        val request = AutonomyHomeController.VersionRequest(fixture.record.version)

        assertEquals(HttpStatus.FORBIDDEN, assertFailsWith<ApiException> { controller.dismiss(fixture.record.id,request) }.status)
        assertEquals(HttpStatus.FORBIDDEN, assertFailsWith<ApiException> { controller.restore(fixture.record.id,request) }.status)
        val unchanged = assertNotNull(service().find(fixture.record.workspaceId,fixture.record.id))
        assertEquals(fixture.record.version,unchanged.version)
        assertFalse(unchanged.dismissed)
        verify(access,times(2)).requireWritable(fixture.record.workspaceId)
    }

    private fun controller(context: DevContext, access: WorkspaceAccessService) = AutonomyHomeController(
        context,access,service(),sql,
    )

    private fun service() = OpportunityService(sql,transactions,mapper,
        CustomerValueAssessmentService(AssessmentGateway { input ->
            AssessmentDecision(AssessmentDisposition.EXCLUDED,"Internal-only maintenance",input.evidence.map { it.id },emptyList())
        },AssessmentProperties()),OpportunityProperties())

    private fun fixture(workspace: UUID = UUID.randomUUID()): Fixture {
        if (workspaces.add(workspace)) jdbc.update(
            "insert into workspaces(id,name,slug,status,created_at,updated_at) values (?,'Home test',?,'ACTIVE',now(),now())",
            workspace,workspace.toString())
        val namespace=UUID.randomUUID()
        val scope=UUID.randomUUID()
        val connection=UUID.randomUUID()
        val binding=UUID.randomUUID()
        jdbc.update("""insert into source_namespaces(id,workspace_id,provider,namespace_kind,external_namespace_key,status,created_at,updated_at)
            values (?,?,'TEST','TENANT',?,'ACTIVE',now(),now())""",namespace,workspace,namespace.toString())
        jdbc.update("""insert into source_scopes(id,workspace_id,source_namespace_id,provider,scope_semantics,scope_kind,external_scope_key,display_name,status,created_at,updated_at)
            values (?,?,?,'TEST','CONTAINER','PROJECT',?,'Test','ACTIVE',now(),now())""",scope,workspace,namespace,scope.toString())
        jdbc.update("""insert into connections(id,workspace_id,provider,connection_kind,external_connection_key,status,created_at,updated_at)
            values (?,?,'TEST','TOKEN',?,'ACTIVE',now(),now())""",connection,workspace,connection.toString())
        jdbc.update("""insert into connection_namespace_bindings(id,workspace_id,provider,connection_id,source_namespace_id,status,valid_from,created_at,updated_at)
            values (?,?,'TEST',?,?,'ACTIVE',now(),now(),now())""",binding,workspace,connection,namespace)
        val input=AssessmentInput(scope,"1",listOf(AssessmentEvidence("release-1","1",AssessmentEvidenceKind.RELEASE,
            "Maintenance","Internal maintenance",CustomerAvailability.AVAILABLE)),"Product")
        return Fixture(service().assess(workspace,scope,"release:1","Maintenance",input),binding)
    }
    private data class Fixture(val record: OpportunityRecord,val bindingId: UUID)
}
