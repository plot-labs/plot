package com.plot.api.autonomy.opportunity

import com.plot.api.autonomy.assessment.*
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.SqlRow
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class OpportunityService(
    private val sql: JooqSqlExecutor,
    manager: PlatformTransactionManager,
    private val mapper: ObjectMapper,
    private val assessment: CustomerValueAssessmentService,
    private val limits: OpportunityProperties,
) {
    private val transactions = TransactionTemplate(manager)
    private fun <T : Any> transaction(block: () -> T): T = requireNotNull(transactions.execute { block() })

    fun ensureMission(workspaceId: UUID, sourceScopeId: UUID, now: Instant = Instant.now()): MissionRecord = transaction {
        access(workspaceId, sourceScopeId)
        sql.update("""insert into autonomy_missions(id,workspace_id,source_scope_id,created_at,updated_at)
            values (?,?,?,?,?) on conflict (workspace_id,source_scope_id) do nothing""",
            UUID.randomUUID(), workspaceId, sourceScopeId, stamp(now), stamp(now))
        mission(workspaceId, sourceScopeId)
    }

    /** Claim in a short transaction, call model outside it, then compare version before committing its result. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun assess(workspaceId: UUID, sourceScopeId: UUID, subjectKey: String, title: String,
               input: AssessmentInput, now: Instant = Instant.now()): OpportunityRecord {
        require(subjectKey.isNotBlank() && subjectKey.length <= 500 && title.isNotBlank() && title.length <= 1000)
        require(input.productScopeId == sourceScopeId) { "Assessment scope mismatch" }
        val frozen = input.copy(evidence = input.canonicalEvidence())
        val fingerprint = frozen.fingerprint()
        val token = UUID.randomUUID()
        val claim = transaction {
            access(workspaceId, sourceScopeId)
            // Lock workspace to serialize budget and goal-count decisions across distinct opportunities.
            sql.query("select id from workspaces where id = ? for update", workspaceId)
            if (ensureMission(workspaceId, sourceScopeId, now).state != "ACTIVE") throw OpportunityException("MISSION_PAUSED")
            sql.update("""insert into autonomy_opportunities(id,workspace_id,source_scope_id,subject_key,title,created_at,updated_at)
                values (?,?,?,?,?,?,?) on conflict (workspace_id,source_scope_id,subject_key) do nothing""",
                UUID.randomUUID(), workspaceId, sourceScopeId, subjectKey, title, stamp(now), stamp(now))
            val item = requireNotNull(findBySubject(workspaceId, sourceScopeId, subjectKey))
            if (item.dismissed) return@transaction Claim(item, false)
            if (item.fingerprint == fingerprint) {
                cancelStaleQueued(workspaceId,item.id,fingerprint,now)
                if (item.disposition == AssessmentDisposition.ELIGIBLE) goal(workspaceId,item,now)
                return@transaction Claim(requireNotNull(find(workspaceId,item.id)), false)
            }
            val cached = sql.queryForObject("""select * from autonomy_assessments
                where workspace_id = ? and opportunity_id = ? and fingerprint = ? for update""",
                { row, _ -> row }, workspaceId, item.id, fingerprint)
            if (cached != null) {
                when (cached.getString("status")) {
                    "SUCCEEDED" -> {
                        val result = mapper.readValue(cached.getString("result"), AssessmentResult::class.java)
                        apply(workspaceId, item, title, frozen, result, now)
                        return@transaction Claim(requireNotNull(find(workspaceId, item.id)), false)
                    }
                    "PROCESSING" -> if (requireNotNull(cached.getTimestamp("lease_until")).toInstant() > now)
                        throw OpportunityException("ASSESSMENT_IN_PROGRESS", true)
                    "FAILED" -> if (!cached.getBoolean("recoverable"))
                        throw OpportunityException(requireNotNull(cached.getString("error_code")))
                }
                if (cached.getInt("attempts") >= limits.maxAssessmentAttempts) throw OpportunityException("ASSESSMENT_ATTEMPTS_EXHAUSTED")
            }
            val count = sql.update("""insert into autonomy_daily_budgets(workspace_id,budget_date,assessment_count)
                values (?,?,1) on conflict (workspace_id,budget_date) do update
                set assessment_count = autonomy_daily_budgets.assessment_count + 1
                where autonomy_daily_budgets.assessment_count < ?""",
                workspaceId, now.atOffset(ZoneOffset.UTC).toLocalDate(), limits.dailyAssessmentLimit)
            if (count != 1) throw OpportunityException("ASSESSMENT_DAILY_LIMIT", true)
            sql.update("""insert into autonomy_assessments(workspace_id,opportunity_id,fingerprint,status,claim_token,lease_until)
                values (?,?,?,'PROCESSING',?,?) on conflict (workspace_id,opportunity_id,fingerprint) do update
                set status='PROCESSING',claim_token=excluded.claim_token,lease_until=excluded.lease_until,
                attempts=autonomy_assessments.attempts+1,error_code=null""",
                workspaceId,item.id,fingerprint,token,stamp(now.plusSeconds(limits.assessmentLeaseSeconds)))
            sql.update("update autonomy_opportunities set version=version+1,updated_at=? where workspace_id=? and id=?",stamp(now),workspaceId,item.id)
            Claim(requireNotNull(find(workspaceId,item.id)), true)
        }
        if (!claim.run) return claim.item
        val result = try { assessment.assess(frozen) } catch (failure: AssessmentException) {
            sql.update("""update autonomy_assessments set status='FAILED',error_code=?,recoverable=?
                where workspace_id=? and opportunity_id=? and fingerprint=? and claim_token=? and status='PROCESSING'""",
                failure.code, failure.recoverable, workspaceId, claim.item.id, fingerprint, token)
            sql.update("update autonomy_opportunities set last_error_code=?,updated_at=now() where workspace_id=? and id=? and version=?",
                failure.code,workspaceId,claim.item.id,claim.item.version)
            throw failure
        }
        return transaction {
            access(workspaceId,sourceScopeId)
            sql.query("select id from workspaces where id=? for update",workspaceId)
            val current = lock(workspaceId,claim.item.id)
            val updated = sql.update("""update autonomy_assessments set status='SUCCEEDED',result=?::jsonb
                where workspace_id=? and opportunity_id=? and fingerprint=? and claim_token=? and status='PROCESSING' and lease_until>?""",
                mapper.writeValueAsString(result),workspaceId,current.id,fingerprint,token,stamp(Instant.now()))
            if (updated != 1) throw OpportunityException("ASSESSMENT_CLAIM_LOST",true)
            if (current.version != claim.item.version || current.dismissed) return@transaction current
            if (mission(workspaceId, sourceScopeId).state != "ACTIVE") throw OpportunityException("MISSION_PAUSED")
            apply(workspaceId,current,title,frozen,result,now)
            requireNotNull(find(workspaceId,current.id))
        }
    }

    fun find(workspaceId: UUID, id: UUID): OpportunityRecord? = sql.queryForObject(
        "$SELECT where o.workspace_id=? and o.id=?", { row, _ -> record(row) },workspaceId,id)
    fun findBySubject(workspaceId: UUID, sourceScopeId: UUID, subjectKey: String): OpportunityRecord? = sql.queryForObject(
        "$SELECT where o.workspace_id=? and o.source_scope_id=? and o.subject_key=?", { row, _ -> record(row) }, workspaceId,sourceScopeId,subjectKey)
    fun list(workspaceId: UUID): List<OpportunityRecord> = sql.query(
        "$SELECT where o.workspace_id=? order by o.updated_at desc,o.id limit 200", { row, _ -> record(row) },workspaceId)

    fun dismiss(workspaceId: UUID,id: UUID,expectedVersion: Long): OpportunityRecord = action(workspaceId,id,expectedVersion) { item ->
        sql.update("update autonomy_opportunities set dismissed=true,version=version+1,updated_at=now() where workspace_id=? and id=?",workspaceId,id)
        // A running execution's immutable snapshot is untouched; no replacement execution is started.
        sql.update("update autonomy_goals set state='CANCELLED',updated_at=now() where workspace_id=? and opportunity_id=? and state='QUEUED'",workspaceId,item.id)
    }
    fun restore(workspaceId: UUID,id: UUID,expectedVersion: Long): OpportunityRecord = action(workspaceId,id,expectedVersion) {
        sql.update("update autonomy_opportunities set dismissed=false,version=version+1,updated_at=now() where workspace_id=? and id=?",workspaceId,id)
    }
    fun prepare(workspaceId: UUID,id: UUID,expectedVersion: Long): OpportunityRecord = action(workspaceId,id,expectedVersion) { item ->
        if (item.dismissed || item.disposition != AssessmentDisposition.ELIGIBLE) throw OpportunityException("OPPORTUNITY_NOT_ELIGIBLE")
        goal(workspaceId,item,Instant.now())
    }
    fun linkAgent(workspaceId: UUID,goalId: UUID,agentRunId: UUID) = transaction {
        val item = sql.queryForObject("""select o.id from autonomy_opportunities o join autonomy_goals g
            on g.workspace_id=o.workspace_id and g.opportunity_id=o.id where g.workspace_id=? and g.id=?""",
            UUID::class.java,workspaceId,goalId) ?: throw OpportunityException("GOAL_NOT_FOUND")
        val current = lock(workspaceId,item)
        access(workspaceId,current.sourceScopeId)
        if (current.dismissed || mission(workspaceId,current.sourceScopeId).state != "ACTIVE") throw OpportunityException("GOAL_NOT_ACTIVE")
        if (sql.update("""update autonomy_goals set agent_run_id=?,state='RUNNING',updated_at=now()
            where workspace_id=? and id=? and ((state='QUEUED' and agent_run_id is null) or (state='RUNNING' and agent_run_id=?))
            and fingerprint=? and exists(select 1 from autonomy_opportunities o where o.workspace_id=autonomy_goals.workspace_id
            and o.id=autonomy_goals.opportunity_id and o.disposition='ELIGIBLE' and not o.dismissed and o.fingerprint=autonomy_goals.fingerprint)""",
            agentRunId,workspaceId,goalId,agentRunId,current.fingerprint) != 1) throw OpportunityException("GOAL_ALREADY_LINKED")
        true
    }

    private fun apply(workspace: UUID,item: OpportunityRecord,title: String,input: AssessmentInput,result: AssessmentResult,now: Instant) {
        sql.update("""update autonomy_opportunities set title=?,fingerprint=?,disposition=?,reason=?,input_snapshot=?::jsonb,
            evidence_ids=?::jsonb,missing_facts=?::jsonb,last_error_code=null,version=version+1,updated_at=? where workspace_id=? and id=?""",
            title,result.fingerprint,result.disposition.name,result.reason,mapper.writeValueAsString(input),
            mapper.writeValueAsString(result.evidenceIds),mapper.writeValueAsString(result.missingFacts),stamp(now),workspace,item.id)
        cancelStaleQueued(workspace,item.id,result.fingerprint,now)
        if (result.disposition == AssessmentDisposition.ELIGIBLE) goal(workspace,requireNotNull(find(workspace,item.id)),now)
    }
    private fun cancelStaleQueued(workspace: UUID,id: UUID,fingerprint: String,now: Instant) {
        sql.update("""update autonomy_goals set state='CANCELLED',updated_at=? where workspace_id=? and opportunity_id=?
            and state='QUEUED' and agent_run_id is null and fingerprint<>?""",stamp(now),workspace,id,fingerprint)
    }
    private fun goal(workspace: UUID,item: OpportunityRecord,now: Instant): GoalRecord? {
        item.fingerprint?.let { cancelStaleQueued(workspace,item.id,it,now) }
        val existing = goalByOpportunity(workspace,item.id)
        if (existing != null && (existing.state in setOf("QUEUED","RUNNING") ||
            (existing.fingerprint == item.fingerprint && existing.state != "CANCELLED"))) return existing
        val active = sql.queryForObject("select count(*) from autonomy_goals where workspace_id=? and state in ('QUEUED','RUNNING')", Int::class.java,workspace) ?: 0
        if (active >= limits.activeGoalLimit) {
            sql.update("update autonomy_opportunities set last_error_code='ACTIVE_GOAL_LIMIT' where workspace_id=? and id=?",workspace,item.id)
            return null
        }
        // Restoring a dismissed, never-admitted goal is safe; retain its immutable snapshot and identity.
        if (existing?.state == "CANCELLED" && existing.fingerprint == item.fingerprint) {
            sql.update("update autonomy_goals set state='QUEUED',updated_at=? where workspace_id=? and id=? and agent_run_id is null",stamp(now),workspace,existing.id)
            return goalByOpportunity(workspace,item.id)
        }
        sql.update("""insert into autonomy_goals(id,workspace_id,opportunity_id,fingerprint,input_snapshot,created_at,updated_at)
            select ?,workspace_id,id,fingerprint,input_snapshot,?,? from autonomy_opportunities
            where workspace_id=? and id=? and disposition='ELIGIBLE' and not dismissed
            on conflict do nothing""",UUID.randomUUID(),stamp(now),stamp(now),workspace,item.id)
        sql.update("update autonomy_opportunities set last_error_code=null where workspace_id=? and id=?",workspace,item.id)
        return goalByOpportunity(workspace,item.id)
    }
    fun goalByOpportunity(workspace: UUID,id: UUID): GoalRecord? = sql.queryForObject(
        """select g.* from autonomy_goals g join autonomy_opportunities o on o.workspace_id=g.workspace_id and o.id=g.opportunity_id
        where g.workspace_id=? and g.opportunity_id=? order by (g.state in ('QUEUED','RUNNING')) desc,
        (g.fingerprint=o.fingerprint) desc,g.created_at desc,g.id desc limit 1""",{ row,_ ->
            GoalRecord(row.uuid("id"),row.uuid("opportunity_id"),requireNotNull(row.getString("state")),row.getObject("agent_run_id",UUID::class.java),requireNotNull(row.getString("fingerprint")))
        },workspace,id)
    private fun action(workspace: UUID,id: UUID,version: Long,change: (OpportunityRecord)->Unit): OpportunityRecord = transaction {
        sql.query("select id from workspaces where id=? for update",workspace)
        val item=lock(workspace,id)
        access(workspace,item.sourceScopeId)
        if(item.version != version) throw OpportunityException("OPPORTUNITY_VERSION_CONFLICT")
        if(mission(workspace,item.sourceScopeId).state != "ACTIVE") throw OpportunityException("MISSION_PAUSED")
        change(item)
        requireNotNull(find(workspace,id))
    }
    private fun lock(workspace: UUID,id: UUID): OpportunityRecord {
        sql.query("select id from autonomy_opportunities where workspace_id=? and id=? for update",workspace,id)
        return find(workspace,id) ?: throw OpportunityException("OPPORTUNITY_NOT_FOUND")
    }
    private fun mission(workspace: UUID,scope: UUID): MissionRecord = requireNotNull(sql.queryForObject(
        "select * from autonomy_missions where workspace_id=? and source_scope_id=? for share",{ row,_ ->
            MissionRecord(row.uuid("id"),workspace,scope,requireNotNull(row.getString("state"))) },workspace,scope))
    private fun access(workspace: UUID,scope: UUID) {
        if(sql.query("""select s.id from source_scopes s join source_namespaces n on n.workspace_id=s.workspace_id and n.id=s.source_namespace_id
            join workspaces w on w.id=s.workspace_id
            join connection_namespace_bindings b on b.workspace_id=s.workspace_id and b.source_namespace_id=s.source_namespace_id
            join connections c on c.workspace_id=b.workspace_id and c.id=b.connection_id
            where s.workspace_id=? and s.id=? and s.status='ACTIVE' and n.status='ACTIVE' and w.status='ACTIVE'
            and b.status='ACTIVE' and b.valid_from<=now() and (b.valid_to is null or b.valid_to>now()) and c.status='ACTIVE'
            for share of s,n,b,c""",workspace,scope).isEmpty()) throw OpportunityException("SOURCE_ACCESS_UNAVAILABLE")
    }
    private fun record(row: SqlRow) = OpportunityRecord(row.uuid("id"),row.uuid("workspace_id"),row.uuid("source_scope_id"),
        requireNotNull(row.getString("subject_key")),requireNotNull(row.getString("title")),AssessmentDisposition.valueOf(requireNotNull(row.getString("disposition"))),
        requireNotNull(row.getString("reason")),row.getString("fingerprint"),row.getBoolean("dismissed"),row.getLong("version"),
        mapper.readValue(requireNotNull(row.getString("evidence_ids")),Array<String>::class.java).toList(),mapper.readValue(requireNotNull(row.getString("missing_facts")),Array<String>::class.java).toList(),
        row.getString("last_error_code"),row.getObject("goal_id",UUID::class.java),row.getString("goal_state"),row.getObject("agent_run_id",UUID::class.java),requireNotNull(row.getTimestamp("updated_at")).toInstant(),row.getObject("chat_id",UUID::class.java))
    private data class Claim(val item: OpportunityRecord,val run: Boolean)
    private companion object {
        const val SELECT="""select o.*,g.id as goal_id,g.state as goal_state,g.agent_run_id,a.work_session_id as chat_id from autonomy_opportunities o
            left join lateral (select * from autonomy_goals candidate where candidate.workspace_id=o.workspace_id and candidate.opportunity_id=o.id
            order by (candidate.state in ('QUEUED','RUNNING')) desc,(candidate.fingerprint=o.fingerprint) desc,candidate.created_at desc,candidate.id desc limit 1) g on true
            left join agent_runs a on a.workspace_id=o.workspace_id and a.id=g.agent_run_id"""
        fun stamp(time: Instant)=Timestamp.from(time)
    }
}
private fun SqlRow.uuid(name: String): UUID = requireNotNull(getObject(name,UUID::class.java))
