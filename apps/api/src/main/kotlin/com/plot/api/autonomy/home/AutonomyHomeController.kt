package com.plot.api.autonomy.home

import com.plot.api.autonomy.signal.ActivityPage
import com.plot.api.autonomy.signal.SignalActivityProjectionService
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.persistence.SqlExecutor
import java.time.Instant
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/autonomy")
class AutonomyHomeController(
    private val context: DevContext,
    private val access: WorkspaceAccessService,
    private val sql: SqlExecutor,
    private val activityProjection: SignalActivityProjectionService? = null,
) {
    companion object {
        private const val MAX_ACTIVITY_PAGE_SIZE = 100
    }

    @GetMapping("/activity")
    fun activity(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false) highWaterMark: String?,
    ): ResponseEntity<ActivityPage> {
        val workspace = context.devWorkspaceId
        access.requireActiveWorkspace(workspace)
        val visibleScopes = getVisibleScopes(workspace)
        val hwm = highWaterMark?.let { Instant.parse(it) }
        val projection = activityProjection ?: SignalActivityProjectionService(sql)
        val pageSize = limit.coerceIn(1, MAX_ACTIVITY_PAGE_SIZE)
        return response(projection.projectActivity(workspace, visibleScopes, pageSize, cursor, hwm))
    }

    private fun getVisibleScopes(workspace: UUID): Set<UUID> = sql.query("""select distinct s.id from source_scopes s
        join source_namespaces n on n.workspace_id=s.workspace_id and n.id=s.source_namespace_id
        join connection_namespace_bindings b on b.workspace_id=s.workspace_id and b.source_namespace_id=n.id
        join connections c on c.workspace_id=b.workspace_id and c.id=b.connection_id
        where s.workspace_id=? and s.status='ACTIVE' and n.status='ACTIVE' and b.status='ACTIVE'
        and c.status='ACTIVE' and b.valid_from<=now() and (b.valid_to is null or b.valid_to>now())""",
        { row, _ -> row.getObject("id", UUID::class.java) }, workspace).filterNotNull().toSet()

    private fun <T : Any> response(value: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
}
