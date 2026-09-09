package com.plot.api.autonomy.home

import com.plot.api.autonomy.opportunity.OpportunityException
import com.plot.api.autonomy.opportunity.OpportunityRecord
import com.plot.api.autonomy.opportunity.OpportunityService
import com.plot.api.common.ApiException
import com.plot.api.dev.DevContext
import com.plot.api.entitlement.WorkspaceAccessService
import com.plot.api.persistence.JooqSqlExecutor
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/autonomy")
class AutonomyHomeController(
    private val context: DevContext,
    private val access: WorkspaceAccessService,
    private val opportunities: OpportunityService,
    private val sql: JooqSqlExecutor,
) {
    data class VersionRequest(val expectedVersion: Long)
    data class HomeResponse(val items: List<HomeItem>)
    data class HomeItem(
        val id: UUID, val sourceScopeId: UUID, val title: String, val disposition: String,
        val reason: String, val dismissed: Boolean, val version: Long, val missingFacts: List<String>,
        val lastErrorCode: String?, val goalState: String?, val agentRunId: UUID?, val chatId: UUID?,
        val updatedAt: java.time.Instant,
    )

    @GetMapping("/home")
    fun home(): ResponseEntity<HomeResponse> {
        val workspace = context.devWorkspaceId
        access.requireActiveWorkspace(workspace)
        val visibleScopes = sql.query("""select distinct s.id from source_scopes s
            join source_namespaces n on n.workspace_id=s.workspace_id and n.id=s.source_namespace_id
            join connection_namespace_bindings b on b.workspace_id=s.workspace_id and b.source_namespace_id=n.id
            join connections c on c.workspace_id=b.workspace_id and c.id=b.connection_id
            where s.workspace_id=? and s.status='ACTIVE' and n.status='ACTIVE' and b.status='ACTIVE'
            and c.status='ACTIVE' and b.valid_from<=now() and (b.valid_to is null or b.valid_to>now())""",
            { row, _ -> row.getObject("id", UUID::class.java) }, workspace).toSet()
        return response(HomeResponse(
            opportunities.list(workspace).filter { it.sourceScopeId in visibleScopes }.map(::item)))
    }

    @PostMapping("/opportunities/{id}/dismiss")
    fun dismiss(@PathVariable id: UUID, @RequestBody request: VersionRequest) = change {
        opportunities.dismiss(context.devWorkspaceId,id,request.expectedVersion)
    }

    @PostMapping("/opportunities/{id}/restore")
    fun restore(@PathVariable id: UUID, @RequestBody request: VersionRequest) = change {
        opportunities.restore(context.devWorkspaceId,id,request.expectedVersion)
    }

    private fun change(action: () -> OpportunityRecord): ResponseEntity<HomeItem> {
        access.requireWritable(context.devWorkspaceId)
        return try { response(item(action())) } catch (failure: OpportunityException) {
            val status = when(failure.code) {
                "OPPORTUNITY_NOT_FOUND", "SOURCE_ACCESS_UNAVAILABLE" -> HttpStatus.NOT_FOUND
                else -> HttpStatus.CONFLICT
            }
            throw ApiException(status, failure.code, "This item changed or is no longer available. Refresh and try again.")
        }
    }

    private fun item(record: OpportunityRecord): HomeItem {
        return HomeItem(record.id,record.sourceScopeId,record.title,record.disposition.name,record.reason,
            record.dismissed,record.version,record.missingFacts,record.lastErrorCode,record.goalState,
            record.agentRunId,record.chatId,record.updatedAt)
    }
    private fun <T : Any> response(value: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
}
