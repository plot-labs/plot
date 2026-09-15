package com.plot.api.autonomy.home

import com.plot.api.TestcontainersConfiguration
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.persistence.SqlExecutor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AutonomyHomeIntegrationTest {
    @Autowired lateinit var sql: SqlExecutor
    @Autowired lateinit var jdbc: JdbcTemplate
    private val workspaces = mutableSetOf<UUID>()

    @AfterEach
    fun cleanup() {
        workspaces.forEach { workspace ->
            listOf("legacy_activity_provenance", "signal_evaluations",
                "autonomy_signal_heads", "autonomy_signals", "source_scopes",
                "connection_namespace_bindings", "connections", "source_namespaces").forEach {
                jdbc.update("delete from $it where workspace_id=?", workspace)
            }
            jdbc.update("delete from workspaces where id=?", workspace)
        }
        workspaces.clear()
    }

    @Test
    fun `activity returns paginated activity items for visible scopes`() {
        val selected = fixture()
        val context = mock(DevContext::class.java)
        val access = mock(WorkspaceAccessService::class.java)
        `when`(context.devWorkspaceId).thenReturn(selected.workspaceId)
        val controller = AutonomyHomeController(context, access, sql)

        jdbc.update("""
            insert into legacy_activity_provenance(id, workspace_id, source_scope_id, title, disposition, reason, semantic_time, created_at)
            values (?, ?, ?, 'Legacy v1.0', 'EXCLUDED', 'No customer value', now(), now())
        """, UUID.randomUUID(), selected.workspaceId, selected.scopeId)

        val response = controller.activity(null, 20, null)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("no-store", response.headers.cacheControl)
        val body = assertNotNull(response.body)
        assertEquals(1, body.items.size)
        assertEquals("Legacy v1.0", body.items[0].title)
        assertEquals("EXCLUDED", body.items[0].status)
        verify(access).requireActiveWorkspace(selected.workspaceId)
    }

    @Test
    fun `activity bounds an oversized requested page`() {
        val selected = fixture()
        val context = mock(DevContext::class.java)
        val access = mock(WorkspaceAccessService::class.java)
        `when`(context.devWorkspaceId).thenReturn(selected.workspaceId)
        val controller = AutonomyHomeController(context, access, sql)

        repeat(101) { index ->
            jdbc.update(
                """
                insert into legacy_activity_provenance(
                    id, workspace_id, source_scope_id, title, disposition, reason, semantic_time, created_at
                ) values (?, ?, ?, ?, 'EXCLUDED', 'No customer value', now() - (? * interval '1 second'), now())
                """.trimIndent(),
                UUID.randomUUID(), selected.workspaceId, selected.scopeId, "Legacy $index", index,
            )
        }

        val response = controller.activity(null, 10_000, null)

        assertEquals(100, assertNotNull(response.body).items.size)
    }

    private fun fixture(workspace: UUID = UUID.randomUUID()): Fixture {
        if (workspaces.add(workspace)) jdbc.update(
            "insert into workspaces(id,name,slug,status,created_at,updated_at) values (?,'Home test',?,'ACTIVE',now(),now())",
            workspace, workspace.toString())
        val namespace = UUID.randomUUID()
        val scope = UUID.randomUUID()
        val connection = UUID.randomUUID()
        val binding = UUID.randomUUID()
        jdbc.update("""insert into source_namespaces(id,workspace_id,provider,namespace_kind,external_namespace_key,status,created_at,updated_at)
            values (?,?,'TEST','TENANT',?,'ACTIVE',now(),now())""", namespace, workspace, namespace.toString())
        jdbc.update("""insert into source_scopes(id,workspace_id,source_namespace_id,provider,scope_semantics,scope_kind,external_scope_key,display_name,status,created_at,updated_at)
            values (?,?,?,'TEST','CONTAINER','PROJECT',?,'Test','ACTIVE',now(),now())""", scope, workspace, namespace, scope.toString())
        jdbc.update("""insert into connections(id,workspace_id,provider,connection_kind,external_connection_key,status,created_at,updated_at)
            values (?,?,'TEST','TOKEN',?,'ACTIVE',now(),now())""", connection, workspace, connection.toString())
        jdbc.update("""insert into connection_namespace_bindings(id,workspace_id,provider,connection_id,source_namespace_id,status,valid_from,created_at,updated_at)
            values (?,?,'TEST',?,?,'ACTIVE',now(),now(),now())""", binding, workspace, connection, namespace)
        return Fixture(workspace, scope, binding)
    }

    private data class Fixture(val workspaceId: UUID, val scopeId: UUID, val bindingId: UUID)
}
