package com.plot.api.certification

import java.time.Instant

enum class DatabaseDisposition { DESTROYED, RESTRICTED_RETAINED, UNRESOLVED }

enum class CleanupCode {
	CLEANUP_COMPLETE,
	LISTENER_STILL_RUNNING,
	CREDENTIAL_NOT_REVOKED,
	STATE_SECRET_NOT_DISPOSED,
	RAW_ARTIFACT_REMAINS,
	BROWSER_ARTIFACT_REMAINS,
	DATABASE_UNDISPOSED,
	RETENTION_OWNER_MISSING,
	RETENTION_EXPIRY_INVALID,
}

data class CertificationCleanupObservation(
	val campaignId: String,
	val campaignManifestHash: String,
	val sourceRevision: String,
	val recordedAt: String,
	val attestedByOperatorAlias: String,
	val attestedAt: String,
	val listenerCount: Int,
	val githubCredentialRevoked: Boolean,
	val openRouterCredentialRevoked: Boolean,
	val stateSecretDisposed: Boolean,
	val rawArtifactsDeleted: Boolean,
	val browserArtifactsDeleted: Boolean,
	val databaseDisposition: DatabaseDisposition,
	val retainedOwnerAlias: String? = null,
	val retainedExpiresAt: String? = null,
)

data class CertificationCleanupResult(
	val schemaVersion: String = "certification-cleanup-v1",
	val campaignId: String,
	val campaignManifestHash: String,
	val sourceRevision: String,
	val recordedAt: String,
	val attestedByOperatorAlias: String,
	val attestedAt: String,
	val outcome: EvidenceOutcome,
	val codes: List<CleanupCode>,
	val listenerCount: Int,
	val githubCredentialRevoked: Boolean,
	val openRouterCredentialRevoked: Boolean,
	val stateSecretDisposed: Boolean,
	val rawArtifactsDeleted: Boolean,
	val browserArtifactsDeleted: Boolean,
	val databaseDisposition: DatabaseDisposition,
	val retainedOwnerAlias: String?,
	val retainedExpiresAt: String?,
)

class CertificationCleanupGate {
	fun evaluate(observation: CertificationCleanupObservation, now: Instant = Instant.now()): CertificationCleanupResult {
		require(observation.listenerCount >= 0) { "listener count cannot be negative" }
		require(Regex("^campaign-[a-f0-9]{16,64}$").matches(observation.campaignId))
		require(Regex("^sha256:[a-f0-9]{64}$").matches(observation.campaignManifestHash))
		require(Regex("^[a-f0-9]{40}$").matches(observation.sourceRevision))
		require(Regex("^operator-[a-f0-9]{16,64}$").matches(observation.attestedByOperatorAlias))
		val recordedAt = Instant.parse(observation.recordedAt)
		val attestedAt = Instant.parse(observation.attestedAt)
		require(!recordedAt.isAfter(now) && !attestedAt.isAfter(recordedAt))
		val codes = linkedSetOf<CleanupCode>()
		if (observation.listenerCount != 0) codes += CleanupCode.LISTENER_STILL_RUNNING
		if (!observation.githubCredentialRevoked || !observation.openRouterCredentialRevoked) {
			codes += CleanupCode.CREDENTIAL_NOT_REVOKED
		}
		if (!observation.stateSecretDisposed) codes += CleanupCode.STATE_SECRET_NOT_DISPOSED
		if (!observation.rawArtifactsDeleted) codes += CleanupCode.RAW_ARTIFACT_REMAINS
		if (!observation.browserArtifactsDeleted) codes += CleanupCode.BROWSER_ARTIFACT_REMAINS
		when (observation.databaseDisposition) {
			DatabaseDisposition.DESTROYED -> if (observation.retainedOwnerAlias != null || observation.retainedExpiresAt != null) {
				codes += CleanupCode.DATABASE_UNDISPOSED
			}
			DatabaseDisposition.RESTRICTED_RETAINED -> {
				if (observation.retainedOwnerAlias?.matches(OPAQUE_OWNER) != true) codes += CleanupCode.RETENTION_OWNER_MISSING
				val expiry = runCatching { Instant.parse(observation.retainedExpiresAt) }.getOrNull()
				if (expiry == null || !expiry.isAfter(now)) codes += CleanupCode.RETENTION_EXPIRY_INVALID
			}
			DatabaseDisposition.UNRESOLVED -> codes += CleanupCode.DATABASE_UNDISPOSED
		}
		if (codes.isEmpty()) codes += CleanupCode.CLEANUP_COMPLETE
		return CertificationCleanupResult(
			campaignId = observation.campaignId,
			campaignManifestHash = observation.campaignManifestHash,
			sourceRevision = observation.sourceRevision,
			recordedAt = observation.recordedAt,
			attestedByOperatorAlias = observation.attestedByOperatorAlias,
			attestedAt = observation.attestedAt,
			outcome = if (codes == setOf(CleanupCode.CLEANUP_COMPLETE)) EvidenceOutcome.PASS else EvidenceOutcome.HARD_GATE_FAIL,
			codes = codes.toList(),
			listenerCount = observation.listenerCount,
			githubCredentialRevoked = observation.githubCredentialRevoked,
			openRouterCredentialRevoked = observation.openRouterCredentialRevoked,
			stateSecretDisposed = observation.stateSecretDisposed,
			rawArtifactsDeleted = observation.rawArtifactsDeleted,
			browserArtifactsDeleted = observation.browserArtifactsDeleted,
			databaseDisposition = observation.databaseDisposition,
			retainedOwnerAlias = observation.retainedOwnerAlias,
			retainedExpiresAt = observation.retainedExpiresAt,
		)
	}

	companion object {
		private val OPAQUE_OWNER = Regex("^owner-[a-f0-9]{16,64}$")
	}
}
