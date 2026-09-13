package com.plot.api.autonomy.github

import com.plot.api.autonomy.signal.SignalEnvelope
import com.plot.api.autonomy.signal.SignalInbox
import com.plot.api.github.*
import com.plot.api.persistence.JooqSqlExecutor
import java.util.UUID
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class GitHubAutonomyBridge(
    private val inbox: SignalInbox,
    private val sql: JooqSqlExecutor,
    private val mapper: ObjectMapper,
) {
    fun bootstrap(workspaceId: UUID, sourceScopeId: UUID) {
        // Legacy mission hierarchy removed
    }

    fun observe(context: GitHubReleaseSourceContext, delivery: GitHubWebhookDelivery, webhook: ParsedGitHubWebhook) {
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
                and agent_run_id is null""",
                context.workspaceId, context.sourceScopeId, webhook.tagName)
        }
    }

    companion object {
        fun subject(tag: String) = "github-release:$tag"
    }
}
