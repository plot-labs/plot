package com.plot.api.autonomy.github

import com.plot.api.autonomy.signal.SignalEvaluationPersistence
import com.plot.api.autonomy.signal.SignalInbox
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Materializes durable webhook observations into Signal evaluation records. */
@Component
class GitHubSignalProjection(
    private val inbox: SignalInbox,
    private val sql: JooqSqlExecutor,
    private val transactions: JooqTransactionExecutor,
    private val signalEvaluationPersistence: SignalEvaluationPersistence? = null,
) {
    @Scheduled(fixedDelayString = "\${plot.autonomy.scan-delay:PT30S}", initialDelayString = "\${plot.autonomy.scan-delay:PT30S}")
    fun scan() {
        inbox.failExhausted("GITHUB", Instant.now())
        for (index in 0 until 50) { if (!projectNext()) break }
    }

    fun projectNext(): Boolean {
        val claim = inbox.claim("GITHUB", Instant.now(), Duration.ofSeconds(30)) ?: return false
        try {
            transactions.execute {
                val now = Instant.now()
                if (!inbox.isCurrent(claim, now)) {
                    inbox.finish(claim, now, superseded = true)
                    return@execute false
                }
                val envelope = claim.envelope
                val isRelease = envelope.objectKey.startsWith("release:")
                val evalPersistence = signalEvaluationPersistence ?: SignalEvaluationPersistence(sql, com.plot.api.common.UuidGenerator())
                evalPersistence.assertWriterInvariants(legacyWriterActive = false)
                val inputFingerprint = java.security.MessageDigest.getInstance("SHA-256")
                    .digest("${envelope.sourceScopeId}:${envelope.objectKey}:${envelope.sourceVersion}".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val tagName = if (isRelease) envelope.objectKey.removePrefix("release:") else null
                val draftInfo = tagName?.let { tag ->
                    sql.query(
                        """
                        select r.status, r.agent_run_id, v.id as version_id
                        from github_release_draft_requests r
                        left join chat_response_versions v on v.workspace_id = r.workspace_id and v.agent_run_id = r.agent_run_id
                        where r.workspace_id = ? and r.source_scope_id = ? and r.tag_name = ?
                        """.trimIndent(),
                        { rs, _ ->
                            Triple(
                                rs.getString("status"),
                                rs.getString("agent_run_id")?.let { UUID.fromString(it) },
                                rs.getString("version_id")?.let { UUID.fromString(it) },
                            )
                        },
                        envelope.workspaceId,
                        envelope.sourceScopeId,
                        tag,
                    ).firstOrNull()
                }

                val outcome: String
                val reason: String
                val admittedVersionId: java.util.UUID?

                if (draftInfo?.second != null) {
                    outcome = "ADMITTED"
                    reason = "Release draft admitted: $tagName"
                    admittedVersionId = draftInfo.third
                } else if (draftInfo?.first == "NO_ACTIVITY") {
                    outcome = "NO_GENERATION"
                    reason = "Internal maintenance changes only, no customer value"
                    admittedVersionId = null
                } else if (isRelease) {
                    outcome = "NO_GENERATION"
                    reason = "Release observed: $tagName. Assessment pending."
                    admittedVersionId = null
                } else {
                    outcome = "NO_GENERATION"
                    reason = "Repository changes observed. Assessment pending."
                    admittedVersionId = null
                }

                evalPersistence.recordEvaluation(
                    workspaceId = envelope.workspaceId,
                    signalId = claim.id,
                    sourceNamespaceId = envelope.sourceNamespaceId,
                    sourceScopeId = envelope.sourceScopeId,
                    inputFingerprint = inputFingerprint,
                    outcome = outcome,
                    reason = reason,
                    semanticTime = envelope.sourceVersion ?: now,
                    admittedResponseVersionId = admittedVersionId,
                    now = now,
                )

                check(inbox.finish(claim, Instant.now())) { "Signal lease lost" }
                true
            }
        } catch (failure: RuntimeException) {
            inbox.retry(claim, Instant.now(), Instant.now().plusSeconds(60), "SIGNAL_PROJECTION_FAILED")
        }
        return true
    }
}
