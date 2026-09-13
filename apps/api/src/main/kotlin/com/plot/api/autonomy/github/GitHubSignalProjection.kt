package com.plot.api.autonomy.github

import com.plot.api.autonomy.signal.SignalEvaluationPersistence
import com.plot.api.autonomy.signal.SignalInbox
import com.plot.api.persistence.JooqSqlExecutor
import com.plot.api.persistence.JooqTransactionExecutor
import java.time.Duration
import java.time.Instant
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
                evalPersistence.recordEvaluation(
                    workspaceId = envelope.workspaceId,
                    signalId = claim.id,
                    sourceNamespaceId = envelope.sourceNamespaceId,
                    sourceScopeId = envelope.sourceScopeId,
                    inputFingerprint = inputFingerprint,
                    outcome = "NO_GENERATION",
                    reason = if (isRelease) "Release observed: ${envelope.objectKey.removePrefix("release:")}. Assessment pending." else "Repository changes observed. Assessment pending.",
                    semanticTime = envelope.sourceVersion ?: now,
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
