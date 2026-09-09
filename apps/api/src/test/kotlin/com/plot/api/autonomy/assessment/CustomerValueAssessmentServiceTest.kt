package com.plot.api.autonomy.assessment

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CustomerValueAssessmentServiceTest {
    private val scope = UUID.randomUUID()
    private fun evidence(kind: AssessmentEvidenceKind = AssessmentEvidenceKind.RELEASE, availability: CustomerAvailability = CustomerAvailability.AVAILABLE) =
        AssessmentEvidence("e1", "v1", kind, "chore: fix login", "Customers can now log in", availability)
    private fun input(items: List<AssessmentEvidence> = listOf(evidence())) = AssessmentInput(scope, "1", items, "API customers")
    private fun decision(disposition: AssessmentDisposition = AssessmentDisposition.ELIGIBLE, ids: List<String> = listOf("e1")) =
        AssessmentDecision(disposition, "Restores customer access to their accounts", ids, emptyList())
    private fun service(value: AssessmentDecision) = CustomerValueAssessmentService(AssessmentGateway { value }, AssessmentProperties())

    @Test fun `empty evidence waits without calling model`() {
        val service = CustomerValueAssessmentService(AssessmentGateway { error("must not call") }, AssessmentProperties())
        assertEquals(AssessmentDisposition.AWAITING_EVIDENCE, service.assess(input(emptyList())).disposition)
    }
    @Test fun `chore title does not exclude customer important fix`() {
        assertEquals(AssessmentDisposition.ELIGIBLE, service(decision()).assess(input()).disposition)
    }
    @Test fun `eligible requires available release evidence`() {
        for (item in listOf(evidence(availability = CustomerAvailability.UNKNOWN), evidence(kind = AssessmentEvidenceKind.ISSUE))) {
            assertEquals(AssessmentDisposition.AWAITING_EVIDENCE, service(decision()).assess(input(listOf(item))).disposition)
        }
    }
    @Test fun `invalid citations are rejected`() {
        assertThrows(AssessmentException::class.java) { service(decision(ids = listOf("invented"))).assess(input()) }
    }
    @Test fun `model errors are not customer value decisions`() {
        val service = CustomerValueAssessmentService(AssessmentGateway { throw AssessmentException("PROVIDER_UNAVAILABLE", true) }, AssessmentProperties())
        assertThrows(AssessmentException::class.java) { service.assess(input()) }
    }
    @Test fun `fingerprint is stable for reordered duplicate evidence and changes with context`() {
        val first = evidence()
        val second = first.copy(id = "e2")
        assertEquals(input(listOf(first, second)).fingerprint(), input(listOf(second, first, first)).fingerprint())
        assertNotEquals(input().fingerprint(), input().copy(contextRevision = "2").fingerprint())
        assertNotEquals(input().fingerprint(), input(listOf(first.copy(body = "changed"))).fingerprint())
    }
    @Test fun `blank reason and missing waiting facts are rejected`() {
        assertThrows(AssessmentException::class.java) { service(decision().copy(reason = " ")).assess(input()) }
        assertThrows(AssessmentException::class.java) { service(decision(AssessmentDisposition.AWAITING_EVIDENCE)).assess(input()) }
    }
    @Test fun `published release can still be excluded or accumulated`() {
        for (disposition in listOf(AssessmentDisposition.EXCLUDED, AssessmentDisposition.ACCUMULATING)) {
            assertEquals(disposition, service(decision(disposition)).assess(input()).disposition)
        }
    }
    @Test fun `available release must itself be cited`() {
        val discussion = evidence(kind = AssessmentEvidenceKind.DISCUSSION).copy(id = "e2")
        assertEquals(AssessmentDisposition.AWAITING_EVIDENCE,
            service(decision(ids = listOf("e2"))).assess(input(listOf(evidence(), discussion))).disposition)
    }
    @Test fun `conflicting immutable evidence IDs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            input(listOf(evidence(), evidence().copy(revision = "v2"))).fingerprint()
        }
    }
    @Test fun `input limit prevents provider call`() {
        val service = CustomerValueAssessmentService(AssessmentGateway { error("must not call") }, AssessmentProperties(maxInputCharacters = 1))
        assertThrows(AssessmentException::class.java) { service.assess(input()) }
    }
}
