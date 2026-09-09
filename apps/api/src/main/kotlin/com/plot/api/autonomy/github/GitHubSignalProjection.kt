package com.plot.api.autonomy.github

import com.plot.api.autonomy.opportunity.OpportunityException
import com.plot.api.autonomy.opportunity.OpportunityService
import com.plot.api.autonomy.signal.SignalInbox
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Materializes durable webhook observations. Only release evidence assessment can authorize drafting. */
@Component
class GitHubSignalProjection(
    private val inbox: SignalInbox,
    private val opportunities: OpportunityService,
    private val sql: JooqSqlExecutor,
    private val transactions: JooqTransactionExecutor,
    private val executions: com.plot.api.autonomy.execution.AutonomyExecutionService,
    @org.springframework.context.annotation.Lazy private val dispatcher: com.plot.api.github.GitHubReleaseDraftDispatcher? = null,
    private val limits: com.plot.api.autonomy.opportunity.OpportunityProperties = com.plot.api.autonomy.opportunity.OpportunityProperties(),
) {
    @Scheduled(fixedDelayString = "\${plot.autonomy.scan-delay:PT30S}", initialDelayString = "\${plot.autonomy.scan-delay:PT30S}")
    fun scan() {
        sql.query("select distinct workspace_id from autonomy_goals where state='RUNNING' limit 100",
            { row, _ -> requireNotNull(row.getObject("workspace_id",UUID::class.java)) }).forEach(executions::reconcile)
        if (requeueChangedContext() > 0) dispatcher?.dispatch()
        inbox.failExhausted("GITHUB", Instant.now())
        for (index in 0 until 50) { if (!projectNext()) break }
    }

    /** Context changes can wake a held release; elapsed time alone never promotes it. */
    fun requeueChangedContext(): Int {
        val candidates=sql.query("""select r.id,r.workspace_id from github_release_draft_requests r
            join autonomy_opportunities o on o.workspace_id=r.workspace_id and o.source_scope_id=r.source_scope_id
                and o.subject_key='github-release:'||r.tag_name
            join source_scopes s on s.workspace_id=r.workspace_id and s.id=r.source_scope_id
            left join workspace_content_profiles p on p.workspace_id=r.workspace_id
            where r.status='DEFERRED' and r.agent_run_id is null
                and not o.dismissed and o.input_snapshot is not null
                and (o.input_snapshot->>'contextRevision' is distinct from
                    coalesce(p.current_revision_id::text,'none')||':'||s.status_changed_at::text
                    or (o.disposition='ELIGIBLE' and o.last_error_code='ACTIVE_GOAL_LIMIT' and
                        (select count(*) from autonomy_goals g where g.workspace_id=o.workspace_id and g.state in ('QUEUED','RUNNING')) < ?))
            order by r.updated_at,r.id limit 100""",limits.activeGoalLimit)
        return candidates.sumOf { row ->
            val workspace=requireNotNull(row.getObject("workspace_id",UUID::class.java))
            sql.update("""update github_release_draft_requests
                set status='QUEUED',attempt_count=0,generation_attempt=generation_attempt+1,finished_at=null,
                next_attempt_at=now(),transition_version=transition_version+1,updated_at=now()
                where workspace_id=? and id=? and status='DEFERRED' and agent_run_id is null""",
                workspace,row.getObject("id",UUID::class.java))
        }
    }

    fun projectNext(): Boolean {
        val claim = inbox.claim("GITHUB",Instant.now(),Duration.ofSeconds(30)) ?: return false
        try {
            transactions.execute {
                val now=Instant.now()
                if (!inbox.isCurrent(claim,now)) {
                    inbox.finish(claim,now,superseded=true)
                    return@execute false
                }
                val envelope=claim.envelope
                opportunities.ensureMission(envelope.workspaceId,envelope.sourceScopeId)
                val isRelease = envelope.objectKey.startsWith("release:")
                val key = if(isRelease) GitHubAutonomyBridge.subject(envelope.objectKey.removePrefix("release:")) else "github:${envelope.objectKey}"
                sql.update("""insert into autonomy_opportunities(id,workspace_id,source_scope_id,subject_key,title,
                    disposition,reason,missing_facts,created_at,updated_at)
                    values (?,?,?,?,?,'AWAITING_EVIDENCE',?,'["Published release evidence"]'::jsonb,?,?)
                    on conflict (workspace_id,source_scope_id,subject_key) do nothing""",
                    UUID.randomUUID(),envelope.workspaceId,envelope.sourceScopeId,key,
                    if(isRelease) envelope.objectKey.removePrefix("release:") else "Repository changes",
                    "Change observed. Waiting for release evidence and customer-value assessment.",Timestamp.from(now),Timestamp.from(now))
                check(inbox.finish(claim,Instant.now())) { "Signal lease lost" }
                true
            }
        } catch (failure: RuntimeException) {
            val code = (failure as? OpportunityException)?.code ?: "SIGNAL_PROJECTION_FAILED"
            inbox.retry(claim,Instant.now(),Instant.now().plusSeconds(60),code)
        }
        return true
    }
}
