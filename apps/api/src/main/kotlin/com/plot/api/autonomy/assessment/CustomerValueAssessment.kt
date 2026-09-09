package com.plot.api.autonomy.assessment

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service

enum class AssessmentDisposition { EXCLUDED, ACCUMULATING, AWAITING_EVIDENCE, ELIGIBLE }
enum class AssessmentEvidenceKind { RELEASE, CHANGE, ISSUE, DISCUSSION, DOCUMENT }
enum class CustomerAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

data class AssessmentEvidence(
    val id: String,
    val revision: String,
    val kind: AssessmentEvidenceKind,
    val title: String,
    val body: String,
    val availability: CustomerAvailability,
)

data class AssessmentInput(
    val productScopeId: UUID,
    val contextRevision: String,
    val evidence: List<AssessmentEvidence>,
    val productContext: String,
    val policyVersion: String = "customer-value-v1",
) {
    // Length-prefixed fields avoid delimiter collisions; delivery order and duplicate snapshots do not affect identity.
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        listOf(productScopeId.toString(), contextRevision, productContext, policyVersion).forEach(::add)
        canonicalEvidence().forEach { item ->
            listOf(item.id, item.revision, item.kind.name, item.title, item.body, item.availability.name).forEach(::add)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun canonicalEvidence(): List<AssessmentEvidence> {
        require(evidence.all { it.id.isNotBlank() && it.revision.isNotBlank() })
        require(evidence.groupBy { it.id }.values.all { versions -> versions.distinct().size == 1 }) {
            "An evidence ID must identify exactly one immutable snapshot"
        }
        return evidence.distinct().sortedBy { it.id }
    }
}

data class AssessmentDecision(
    val disposition: AssessmentDisposition,
    val reason: String,
    val evidenceIds: List<String>,
    val missingFacts: List<String>,
)

data class AssessmentResult(
    val fingerprint: String,
    val disposition: AssessmentDisposition,
    val reason: String,
    val evidenceIds: List<String>,
    val missingFacts: List<String>,
)

fun interface AssessmentGateway {
    fun assess(input: AssessmentInput): AssessmentDecision
}

class AssessmentException(val code: String, val recoverable: Boolean, cause: Throwable? = null) :
    RuntimeException(code, cause)

@ConfigurationProperties("plot.autonomy.assessment")
data class AssessmentProperties(
    val maxInputCharacters: Int = 40_000,
    val maxEvidenceItems: Int = 100,
    val maxOutputTokens: Int = 1_000,
) {
    init { require(maxInputCharacters > 0 && maxEvidenceItems > 0 && maxOutputTokens > 0) }
}

@Service
class CustomerValueAssessmentService(
    private val gateway: AssessmentGateway,
    private val properties: AssessmentProperties,
) {
    // Exactly one bounded model invocation. Persistence owns deduplication and any bounded retry policy.
    fun assess(input: AssessmentInput): AssessmentResult {
        val items = input.canonicalEvidence()
        val frozen = input.copy(evidence = items)
        val fingerprint = frozen.fingerprint()
        if (items.isEmpty()) return AssessmentResult(fingerprint, AssessmentDisposition.AWAITING_EVIDENCE,
            "Customer impact cannot be assessed without evidence", emptyList(), listOf("Evidence of a product change"))
        val characters = items.sumOf { it.id.length.toLong() + it.revision.length + it.title.length + it.body.length } +
            input.productContext.length + input.contextRevision.length + input.policyVersion.length
        if (items.size > properties.maxEvidenceItems || characters > properties.maxInputCharacters) {
            throw AssessmentException("ASSESSMENT_INPUT_LIMIT", false)
        }
        val decision = gateway.assess(frozen)
        val ids = decision.evidenceIds.distinct()
        if (decision.reason.isBlank() || decision.reason.length > 2_000 || ids.isEmpty() ||
            ids.any { id -> items.none { it.id == id } } || decision.missingFacts.size > 20 ||
            decision.missingFacts.any { it.isBlank() || it.length > 500 } ||
            (decision.disposition == AssessmentDisposition.AWAITING_EVIDENCE && decision.missingFacts.isEmpty())) {
            throw AssessmentException("ASSESSMENT_INVALID_DECISION", false)
        }
        if (decision.disposition == AssessmentDisposition.ELIGIBLE && items.none {
                it.id in ids && it.kind == AssessmentEvidenceKind.RELEASE && it.availability == CustomerAvailability.AVAILABLE
            }) {
            return AssessmentResult(fingerprint, AssessmentDisposition.AWAITING_EVIDENCE,
                "Customer availability must be verified before preparing a release announcement", ids,
                (decision.missingFacts + "Cited evidence of a customer-available release").distinct())
        }
        return AssessmentResult(fingerprint, decision.disposition, decision.reason, ids, decision.missingFacts.toList())
    }
}
