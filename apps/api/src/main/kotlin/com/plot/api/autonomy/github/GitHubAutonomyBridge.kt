package com.plot.api.autonomy.github

import com.plot.api.autonomy.assessment.*
import com.plot.api.autonomy.opportunity.OpportunityService
import com.plot.api.autonomy.signal.SignalEnvelope
import com.plot.api.autonomy.signal.SignalInbox
import com.plot.api.github.*
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.time.Instant
import java.util.UUID
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

enum class AutonomyMode { OFF, SHADOW, ACTIVE }
@ConfigurationProperties("plot.autonomy")
data class AutonomyProperties(val mode: AutonomyMode = AutonomyMode.OFF, val workspaceIds: Set<UUID> = emptySet()) {
    fun modeFor(workspace: UUID) = if (workspaceIds.isEmpty() || workspace in workspaceIds) mode else AutonomyMode.OFF
}
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AutonomyProperties::class)
class AutonomyConfiguration

@Component
class GitHubAutonomyBridge(
    private val properties: AutonomyProperties,
    private val opportunities: OpportunityService,
    private val inbox: SignalInbox,
    private val sql: JooqSqlExecutor,
    private val transactions: JooqTransactionExecutor,
    private val mapper: ObjectMapper,
    private val executions: com.plot.api.autonomy.execution.AutonomyExecutionService,
) : GitHubReleasePreparationGate {
    fun isActive(workspaceId: UUID) = properties.modeFor(workspaceId) == AutonomyMode.ACTIVE

    fun bootstrap(workspaceId: UUID, sourceScopeId: UUID) {
        if (properties.modeFor(workspaceId) != AutonomyMode.OFF) opportunities.ensureMission(workspaceId, sourceScopeId)
    }

    fun observe(context: GitHubReleaseSourceContext, delivery: GitHubWebhookDelivery, webhook: ParsedGitHubWebhook) {
        if (properties.modeFor(context.workspaceId) == AutonomyMode.OFF) return
        bootstrap(context.workspaceId, context.sourceScopeId)
        // Webhook payload has no authoritative object version. Keep this as an unordered observation.
        inbox.accept(SignalEnvelope(
            context.workspaceId, context.sourceNamespaceId, context.sourceScopeId, "GITHUB",
            delivery.externalDeliveryId, webhook.tagName?.let { "release:$it" } ?: "ref:${webhook.ref.orEmpty()}",
            webhook.eventType, null, mapper.writeValueAsString(mapOf(
                "deliveryId" to delivery.id, "tag" to webhook.tagName, "action" to webhook.eventAction,
                "before" to webhook.beforeSha, "after" to webhook.afterSha, "payloadHash" to webhook.payloadHash,
            )),
        ), delivery.receivedAt)
        // A published event can arrive after the tag's earlier assessment. It changes availability evidence.
        if (webhook.eventType == "release" && webhook.eventAction == "published") {
            sql.update("""update github_release_draft_requests set status='QUEUED', next_attempt_at=now(),
                attempt_count=0, generation_attempt=generation_attempt+1, finished_at=null, transition_version=transition_version+1, updated_at=now()
                where workspace_id=? and source_scope_id=? and tag_name=? and status='DEFERRED'
                and autonomy_mode='ACTIVE' and agent_run_id is null""",
                context.workspaceId, context.sourceScopeId, webhook.tagName)
        }
    }

    override fun shouldPrepare(request: GitHubReleaseDraftRequest, context: GitHubReleaseSourceContext, evidence: GitHubReleaseEvidence): Boolean {
        val mode = transactions.execute {
            // Existing requests were pinned OFF by migration. New requests choose their owner once.
            sql.update("""update github_release_draft_requests set autonomy_mode=?
                where workspace_id=? and id=? and transition_version=? and autonomy_mode is null""",
                properties.modeFor(request.workspaceId).name, request.workspaceId, request.id, request.transitionVersion)
            AutonomyMode.valueOf(requireNotNull(sql.queryForObject(
                "select autonomy_mode from github_release_draft_requests where workspace_id=? and id=?",
                String::class.java, request.workspaceId, request.id)))
        }
        if (mode == AutonomyMode.OFF) return true
        if (mode == AutonomyMode.ACTIVE) executions.reconcile(request.workspaceId)
        val published = sql.queryForObject("""select exists(select 1 from github_webhook_deliveries
            where installation_id=? and repository_id=? and tag_name=? and event_type='release' and event_action='published')
            and not exists(select 1 from github_webhook_deliveries where installation_id=? and repository_id=? and tag_name=?
            and ((event_type='release' and event_action in ('deleted','unpublished')) or ref_deleted=true or forced=true))""",
            Boolean::class.java, context.installationId, context.repositoryId, request.tagName,
            context.installationId, context.repositoryId, request.tagName) == true
        val profile = sql.query("""select r.id,r.product_summary,r.primary_audience from workspace_content_profiles p
            join workspace_content_profile_revisions r on r.workspace_id=p.workspace_id and r.id=p.current_revision_id
            where p.workspace_id=?""", request.workspaceId).firstOrNull()
        val scopeRevision = sql.queryForObject("select status_changed_at::text from source_scopes where workspace_id=? and id=?",
            String::class.java, request.workspaceId, request.sourceScopeId).orEmpty()
        val items = evidence.writingBlockIds.map { id ->
            val row = sql.query("""select b.title,b.body,b.content_hash from writing_blocks b
                join writing_block_scopes m on m.workspace_id=b.workspace_id and m.writing_block_id=b.id
                where b.workspace_id=? and b.id=? and m.source_scope_id=? and m.status='ACTIVE' and b.status='ACTIVE'""",
                request.workspaceId, id, request.sourceScopeId).firstOrNull()
                ?: throw GitHubReleasePermanentException("GITHUB_RELEASE_EVIDENCE_UNAVAILABLE")
            AssessmentEvidence(id.toString(), row.getString("content_hash") ?: evidence.observationId.toString(),
                AssessmentEvidenceKind.CHANGE, row.getString("title").orEmpty(), row.getString("body").orEmpty(), CustomerAvailability.UNKNOWN)
        } + AssessmentEvidence("release:${request.tagName}", "${request.baseSha}:${request.headSha}:$published",
            AssessmentEvidenceKind.RELEASE, request.tagName,
            "Resolved range ${request.baseSha}...${request.headSha}. Published release observed: $published. Individual feature availability is not implied.",
            if (published) CustomerAvailability.AVAILABLE else CustomerAvailability.UNKNOWN)
        val input = AssessmentInput(request.sourceScopeId, "${profile?.getObject("id") ?: "none"}:$scopeRevision", items,
            "Repository ${context.owner}/${context.repository}. " +
                "Product: ${profile?.getString("product_summary").orEmpty()}. Audience: ${profile?.getString("primary_audience").orEmpty()}")
        val result = try {
            opportunities.assess(request.workspaceId, request.sourceScopeId, subject(request.tagName),
                "${context.repository} · ${request.tagName}", input, createGoal = mode == AutonomyMode.ACTIVE)
        } catch (failure: AssessmentException) {
            if (mode == AutonomyMode.SHADOW) return true
            throw failure
        } catch (failure: com.plot.api.autonomy.opportunity.OpportunityException) {
            if (mode == AutonomyMode.SHADOW && failure.code != "SOURCE_ACCESS_UNAVAILABLE") return true
            throw failure
        }
        if (mode == AutonomyMode.ACTIVE && request.runAttempt > 0) executions.retryFailedGoal(request.workspaceId,result.id)
        val goal = opportunities.goalByOpportunity(request.workspaceId, result.id)
        return mode == AutonomyMode.SHADOW || (!result.dismissed && result.disposition == AssessmentDisposition.ELIGIBLE &&
            goal != null && goal.fingerprint == result.fingerprint && goal.state == "QUEUED" && goal.agentRunId == null)
    }

    override fun admitted(request: GitHubReleaseDraftRequest, agentRunId: UUID) {
        val mode = sql.queryForObject("select autonomy_mode from github_release_draft_requests where workspace_id=? and id=?",
            String::class.java, request.workspaceId, request.id)
        if (mode != "ACTIVE") return
        val item = opportunities.findBySubject(request.workspaceId, request.sourceScopeId, subject(request.tagName)) ?: return
        item.goalId?.let {
            // Compare the actual immutable execution inputs, not mutable source rows observed before the model call.
            val changed=sql.queryForObject("""select exists(select 1 from autonomy_goals g,
                lateral jsonb_array_elements(g.input_snapshot->'evidence') evidence
                where g.workspace_id=? and g.id=? and evidence->>'kind'='CHANGE' and not exists (
                    select 1 from agent_run_inputs i where i.workspace_id=g.workspace_id and i.agent_run_id=?
                    and i.source_scope_id=? and i.writing_block_id::text=evidence->>'id'
                    and coalesce(i.snapshot_title,'')=evidence->>'title'
                    and (i.snapshot_body=evidence->>'body' or
                        (btrim(evidence->>'body')='' and i.snapshot_body=evidence->>'title'))))""",
                Boolean::class.java,request.workspaceId,it,agentRunId,request.sourceScopeId) == true
            if(changed) throw com.plot.api.autonomy.opportunity.OpportunityException("ASSESSMENT_INPUT_CHANGED",true)
            opportunities.linkAgent(request.workspaceId, it, agentRunId)
            executions.admit(request.workspaceId,it,agentRunId)
        }
    }

    companion object {
        fun subject(tag: String) = "github-release:$tag"
    }
}
