package com.plot.api.certification

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CertificationCleanupGateTest {
	private val gate = CertificationCleanupGate()
	private val now = Instant.parse("2026-07-16T00:00:00Z")

	@Test
	fun `destroyed disposable environment passes only after every disposition`() {
		val result = gate.evaluate(observation(), now)

		assertEquals(EvidenceOutcome.PASS, result.outcome)
		assertEquals(listOf(CleanupCode.CLEANUP_COMPLETE), result.codes)
		assertTrue(result.githubCredentialRevoked)
		assertTrue(result.openRouterCredentialRevoked)
	}

	@Test
	fun `any listener secret artifact or database residue blocks GO`() {
		val result = gate.evaluate(observation().copy(
			listenerCount = 2,
			githubCredentialRevoked = false,
			stateSecretDisposed = false,
			rawArtifactsDeleted = false,
			browserArtifactsDeleted = false,
			databaseDisposition = DatabaseDisposition.UNRESOLVED,
		), now)

		assertEquals(EvidenceOutcome.HARD_GATE_FAIL, result.outcome)
		assertTrue(CleanupCode.LISTENER_STILL_RUNNING in result.codes)
		assertTrue(CleanupCode.CREDENTIAL_NOT_REVOKED in result.codes)
		assertTrue(CleanupCode.STATE_SECRET_NOT_DISPOSED in result.codes)
		assertTrue(CleanupCode.RAW_ARTIFACT_REMAINS in result.codes)
		assertTrue(CleanupCode.BROWSER_ARTIFACT_REMAINS in result.codes)
		assertTrue(CleanupCode.DATABASE_UNDISPOSED in result.codes)
	}

	@Test
	fun `restricted retention requires an opaque owner and future expiry`() {
		val valid = gate.evaluate(observation().copy(
			databaseDisposition = DatabaseDisposition.RESTRICTED_RETAINED,
			retainedOwnerAlias = "owner-aaaaaaaaaaaaaaaa",
			retainedExpiresAt = "2026-07-17T00:00:00Z",
		), now)
		val invalid = gate.evaluate(observation().copy(
			databaseDisposition = DatabaseDisposition.RESTRICTED_RETAINED,
			retainedOwnerAlias = "Alice",
			retainedExpiresAt = "2026-07-15T00:00:00Z",
		), now)

		assertEquals(EvidenceOutcome.PASS, valid.outcome)
		assertTrue(CleanupCode.RETENTION_OWNER_MISSING in invalid.codes)
		assertTrue(CleanupCode.RETENTION_EXPIRY_INVALID in invalid.codes)
	}

	private fun observation() = CertificationCleanupObservation(
		campaignId = "campaign-aaaaaaaaaaaaaaaa",
		campaignManifestHash = "sha256:${"a".repeat(64)}",
		sourceRevision = "a".repeat(40),
		recordedAt = "2026-07-16T00:00:00Z",
		attestedByOperatorAlias = "operator-aaaaaaaaaaaaaaaa",
		attestedAt = "2026-07-16T00:00:00Z",
		listenerCount = 0,
		githubCredentialRevoked = true,
		openRouterCredentialRevoked = true,
		stateSecretDisposed = true,
		rawArtifactsDeleted = true,
		browserArtifactsDeleted = true,
		databaseDisposition = DatabaseDisposition.DESTROYED,
	)
}
