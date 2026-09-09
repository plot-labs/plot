package com.plot.api.autonomy.execution

import com.plot.api.autonomy.opportunity.OpportunityException
import com.plot.api.persistence.JooqSqlExecutor
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Version one has one bounded capability: prepare a reviewable draft. AgentRun owns tool steps/retries. */
@Service
class AutonomyExecutionService(private val sql: JooqSqlExecutor,
    private val limits: com.plot.api.autonomy.opportunity.OpportunityProperties = com.plot.api.autonomy.opportunity.OpportunityProperties()) {
    @Transactional
    fun admit(workspaceId: UUID, goalId: UUID, agentRunId: UUID): UUID {
        val goal = sql.query("""select g.id from autonomy_goals g join autonomy_opportunities o
            on o.workspace_id=g.workspace_id and o.id=g.opportunity_id
            where g.workspace_id=? and g.id=? and g.agent_run_id=? and g.state='RUNNING'
            and g.fingerprint=o.fingerprint and o.disposition='ELIGIBLE' and not o.dismissed
            for update of g,o""",workspaceId,goalId,agentRunId)
        if(goal.isEmpty()) throw OpportunityException("GOAL_NOT_ACTIVE")
        sql.queryForObject("select id from autonomy_executions where workspace_id=? and agent_run_id=?",
            UUID::class.java,workspaceId,agentRunId)?.let { return it }
        val version=(sql.queryForObject("select coalesce(max(task_version),0) from autonomy_tasks where workspace_id=? and goal_id=?",
            Int::class.java,workspaceId,goalId) ?: 0)+1
        if(version > 3) throw OpportunityException("GOAL_EXECUTION_LIMIT")
        val taskId=UUID.randomUUID()
        sql.update("""insert into autonomy_tasks(id,workspace_id,goal_id,action,task_version,state)
            values (?,?,?,'PREPARE_REVIEWABLE_DRAFT',?,'RUNNING')""",taskId,workspaceId,goalId,version)
        sql.update("""insert into autonomy_executions(id,workspace_id,task_id,agent_run_id,state)
            values (?,?,?,?,'RUNNING') on conflict (workspace_id,task_id) do nothing""",
            UUID.randomUUID(),workspaceId,taskId,agentRunId)
        return sql.queryForObject("select id from autonomy_executions where workspace_id=? and task_id=? and agent_run_id=?",
            UUID::class.java,workspaceId,taskId,agentRunId) ?: throw OpportunityException("EXECUTION_ALREADY_ADMITTED")
    }

    /** Existing release retry is an explicit new task version; old execution outcomes remain immutable. */
    @Transactional
    fun retryFailedGoal(workspaceId: UUID, opportunityId: UUID) {
        sql.query("select id from workspaces where id=? for update",workspaceId)
        val failed=sql.queryForObject("""select exists(select 1 from autonomy_goals g join autonomy_opportunities o
            on o.workspace_id=g.workspace_id and o.id=g.opportunity_id where o.workspace_id=? and o.id=?
            and g.fingerprint=o.fingerprint and g.state='FAILED')""",Boolean::class.java,workspaceId,opportunityId) == true
        if(!failed) return
        val active=sql.queryForObject("select count(*) from autonomy_goals where workspace_id=? and state in ('QUEUED','RUNNING')",
            Int::class.java,workspaceId) ?: 0
        if(active >= limits.activeGoalLimit) {
            sql.update("update autonomy_opportunities set last_error_code='ACTIVE_GOAL_LIMIT' where workspace_id=? and id=?",workspaceId,opportunityId)
            return
        }
        sql.update("""update autonomy_goals g set state='QUEUED',agent_run_id=null,updated_at=now()
            from autonomy_opportunities o where g.workspace_id=? and o.workspace_id=g.workspace_id
            and o.id=g.opportunity_id and o.id=? and g.fingerprint=o.fingerprint
            and g.state='FAILED' and o.disposition='ELIGIBLE' and not o.dismissed
            and (select count(*) from autonomy_tasks t where t.workspace_id=g.workspace_id and t.goal_id=g.id) < 3""",
            workspaceId,opportunityId)
        sql.update("""update autonomy_opportunities o set last_error_code=null where o.workspace_id=? and o.id=?
            and exists(select 1 from autonomy_goals g where g.workspace_id=o.workspace_id and g.opportunity_id=o.id
                and g.fingerprint=o.fingerprint and g.state='QUEUED')""",workspaceId,opportunityId)
    }

    @Transactional
    fun reconcile(workspaceId: UUID): Int {
        val changed=sql.update("""update autonomy_executions e set
            state=case when a.status='FAILED' then 'FAILED' else 'SUCCEEDED' end,
            outcome=case when a.status='FAILED' then 'FAILED' when exists (
                select 1 from artifact_runs r where r.workspace_id=a.workspace_id and r.agent_run_id=a.id
                and r.status='NEEDS_REVIEW') then 'NEEDS_REVIEW' else 'DRAFT_READY' end,updated_at=now()
            from agent_runs a where e.workspace_id=? and a.workspace_id=e.workspace_id and a.id=e.agent_run_id
            and e.state='RUNNING' and (a.status='FAILED' or (a.status='SUCCEEDED' and exists (
                select 1 from artifact_runs r where r.workspace_id=a.workspace_id and r.agent_run_id=a.id
                and r.status in ('READY','NEEDS_REVIEW'))))""",workspaceId)
        sql.update("""update autonomy_tasks t set state=e.state,updated_at=now() from autonomy_executions e
            where t.workspace_id=? and e.workspace_id=t.workspace_id and e.task_id=t.id
            and t.state='RUNNING' and e.state in ('SUCCEEDED','FAILED','CANCELLED')""",workspaceId)
        sql.update("""update autonomy_goals g set state=t.state,updated_at=now() from autonomy_tasks t
            where g.workspace_id=? and t.workspace_id=g.workspace_id and t.goal_id=g.id
            and exists(select 1 from autonomy_executions e where e.workspace_id=t.workspace_id
                and e.task_id=t.id and e.agent_run_id=g.agent_run_id)
            and g.state='RUNNING' and t.state in ('SUCCEEDED','FAILED','CANCELLED')""",workspaceId)
        return changed
    }
}
